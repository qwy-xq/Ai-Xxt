package com.xq.xxt.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台常驻 Service —— 整个 "按键触发 → 截屏 → AI → 隐藏呈现" 链路的大脑。
 *
 * <h2>按键监听架构（v3）</h2>
 *
 * 通过 {@code Runtime.exec(new String[]{"su", "-c", "getevent -q 2>&1"})} 启动一个
 * root shell 常驻子进程，让内核 input 子系统把硬件事件以 raw 协议格式
 * （十六进制 type/code/value）打到 stdout：
 *
 * <pre>
 *   /dev/input/event4: 0001 0072 00000001     # KEY_VOLUMEDOWN 按下
 *   /dev/input/event4: 0001 0072 00000000     # KEY_VOLUMEDOWN 抬起
 *   /dev/input/event5: 0001 0072 00000001     # 同一物理按键可能在多个 event 节点上报
 * </pre>
 *
 * <p>两条关键修正：</p>
 * <ol>
 *   <li>不再 {@code ProcessBuilder("su")} 然后写 stdin —— Magisk 的 su
 *       在 stdin 关闭时会话立刻结束，{@code getevent} 来不及起来。改用
 *       {@code su -c "..."} 让命令绑定到 su 的整个生命周期；</li>
 *   <li>不再依赖 {@code getevent -l} 输出标签名 —— ColorOS / 部分 ROM 的
 *       toybox getevent 在 root 进程下输出格式不稳。直接用 raw 协议解析，
 *       即 {@code " 0001 0072 ..."} 这种固定格式。</li>
 * </ol>
 *
 * <h2>答案呈现双路径（v5）</h2>
 *
 * <ul>
 *   <li><b>短答案（trim 后长度 ≤ {@value #SHORT_ANSWER_THRESHOLD}）</b>：用
 *       {@link RootCmdExecutor#systemToastAsync} 走系统底层 {@code am broadcast
 *       -a com.android.server.notification.Toast}，不产生应用层 window token，
 *       不会在最近任务里出现悬浮窗；</li>
 *   <li><b>长答案（> {@value #SHORT_ANSWER_THRESHOLD}）</b>：用
 *       {@link NotificationManager#notify} 推 BigTextStyle 通知；</li>
 *   <li><b>am broadcast 降级</b>：短答案 am broadcast 失败时（result.success ==
 *       false 或 callback 抛异常）自动降级到 Notification，确保一定有反馈。</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>{@link #ioHandler}（HandlerThread 派发）：截屏、调用 Vision、am broadcast
 *       这些 IO 调度都走它；</li>
 *   <li>{@link #mainHandler}（主线程 Handler）：所有 UI 动作 —— Toast、
 *       Notification、Log + 落盘 —— 都切回这里做；</li>
 *   <li>ioHandler 里禁止直接 {@code nm.notify} / {@code Toast}。</li>
 * </ul>
 */
public class BackgroundAssistService extends Service {

    private static final String TAG = "AssistSvc";

    public static final String ACTION_TRIGGER = "com.xq.xxt.ai.TRIGGER";

    /** 两次音量减之间的最大间隔（ms） */
    private static final long TRIGGER_WINDOW_MS = 500L;

    /**
     * 短答案阈值：trim 后长度 ≤ 该值（包含等号）走系统 Toast；超过走通知。
     * <p>为什么 = 4：A/B/C/D 单字母 + True/False 都恰好 ≤ 4，能完整覆盖
     * 选择题与判断题；超过 4 的基本上都是问答题文本，必须用 BigTextStyle。
     */
    private static final int SHORT_ANSWER_THRESHOLD = 4;

    private static final String CHANNEL_ID = "assist_results";
    private static final int NOTIF_ID_KEEP_ALIVE = 0xA15E;
    private static final int NOTIF_ID_RESULT = 0xA15F;

    /** 截图文件名（放到 App 私有 cache 目录） */
    private static final String SCREENSHOT_NAME = "screenshot.png";

    /**
     * getevent raw 协议中要识别的关键 token。
     * 一次完整音量减按键产生两行：
     *   "... 0001 0072 00000001"   ← 按下（value=1）
     *   "... 0001 0072 00000000"   ← 抬起（value=0）
     * 这里用 {@code "0001 0072"} 做 type+code 定位，再单独判断 value ≠ 0 即按下。
     */
    private static final String VOL_DOWN_TYPE_CODE = "0001 0072";

    /** 系统 Toast 广播的 action —— ColorOS / 原生 AOSP 都认。 */
    private static final String SYS_TOAST_ACTION = "com.android.server.notification.Toast";

    private HandlerThread ioThread;
    private Handler ioHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean inputListenerAlive = new AtomicBoolean(false);

    private long lastVolDownAt = 0L;
    private int volDownStreak = 0;

    /** getevent 进程，销毁 Service 时需要 forcibly kill */
    private volatile Process geteventProcess;

    /** 供 UI（MainActivity）查询服务运行状态 */
    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    private BroadcastReceiver externalReceiver;

    // ------------------------------------------------------------
    // 调试日志 —— 写到 App 私有 cache 目录，绕过 logcat buffer 滚动
    // ------------------------------------------------------------

    private static final java.io.File DEBUG_LOG_FILE =
            new java.io.File("/data/data/com.xq.xxt.ai/cache/aixxt-debug.log");

    /**
     * 双写：logcat（实时）+ 文件（持久，绕过 logcat 缓冲滚动）。
     * 任何抛错都吞掉 —— debug log 不能影响业务路径。
     */
    private static synchronized void debugLog(@NonNull String tag, @NonNull String msg) {
        Log.i("AiXxtDbg", "[" + tag + "] " + msg);
        try {
            String line = "[" + new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date()) + "] [" + tag + "] " + msg + "\n";
            try (java.io.FileWriter fw = new java.io.FileWriter(DEBUG_LOG_FILE, true);
                 java.io.BufferedWriter bw = new java.io.BufferedWriter(fw)) {
                bw.write(line);
                bw.flush();
            }
        } catch (Throwable t) {
            Log.w("AiXxtDbg", "debugLog file write failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ------------------------------------------------------------
    // Service 生命周期
    // ------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        AiVisionClient.init(this);

        ioThread = new HandlerThread("assist-io");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());

        ensureNotificationChannel(this);

        externalReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(@NonNull Context context, @NonNull Intent intent) {
                String action = intent.getAction();
                if (ACTION_TRIGGER.equals(action)) {
                    triggerPipeline("broadcast:" + ACTION_TRIGGER);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TRIGGER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(externalReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            //noinspection UnspecifiedRegisterReceiverFlag
            registerReceiver(externalReceiver, filter);
        }

        debugLog("onCreate", "service started, scheduling input listener");
        startInputListener();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID_KEEP_ALIVE, buildKeepAliveNotification());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        inputListenerAlive.set(false);
        stopInputListener();
        if (externalReceiver != null) {
            try {
                unregisterReceiver(externalReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (ioThread != null) {
            ioThread.quitSafely();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------
    // /dev/input 监听线程（v3：su -c getevent + raw 协议解析）
    // ------------------------------------------------------------

    private void startInputListener() {
        if (inputListenerAlive.get()) return;
        inputListenerAlive.set(true);
        new Thread(this::inputListenerLoop, "input-listener").start();
        Log.i(TAG, "input listener thread started");
        debugLog("listener", "thread spawned, getevent will start in loop");
    }

    private void stopInputListener() {
        Process p = geteventProcess;
        if (p != null) {
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * getevent 主循环。
     *
     * <p>采用 {@code Runtime.exec(String[])} 数组形式而非 {@code ProcessBuilder + stdin}，
     * 避免 Magisk su 在 stdin EOF 时把整个 shell 收掉导致 getevent 来不及启动。
     * 同时把 stderr 单独接到一个线程读，防止管道满导致 getevent 阻塞。</p>
     */
    private void inputListenerLoop() {
        int retryDelayMs = 1000;
        while (inputListenerAlive.get()) {
            Process process = null;
            BufferedReader reader = null;
            Thread stderrDrainThread = null;
            java.io.ByteArrayOutputStream stderrBuf = new java.io.ByteArrayOutputStream();
            try {
                debugLog("ge", "spawning: su -c getevent -q 2>&1");
                Log.i(TAG, "spawning: su -c getevent -q");

                // 使用数组形式（不会经过 /system/bin/sh -c 的二次解析）
                process = Runtime.getRuntime().exec(new String[]{
                        "su",
                        "-c",
                        "getevent -q 2>&1"
                });
                geteventProcess = process;
                debugLog("ge", "spawned pid=" + safePid(process));

                stderrDrainThread = drainStreamAsync(process.getErrorStream(), "stderr", stderrBuf);

                InputStream stdout = process.getInputStream();
                reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));

                String line;
                int lineCount = 0;
                while (inputListenerAlive.get() && (line = reader.readLine()) != null) {
                    lineCount++;
                    if (lineCount <= 3 || lineCount % 50 == 0) {
                        debugLog("ge-line", "[" + lineCount + "] " + line);
                    }
                    handleGeteventLine(line);
                }
                int exit = process.isAlive() ? -1 : process.exitValue();
                debugLog("ge", "stdout EOF after " + lineCount + " lines, exit=" + exit);
            } catch (Throwable t) {
                Log.w(TAG, "getevent loop crashed: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
                debugLog("ge-err", t.getClass().getSimpleName() + ": " + t.getMessage());
                debugLog("ge-err-stderr", stderrBuf.toString(StandardCharsets.UTF_8));
            } finally {
                geteventProcess = null;
                closeQuietly(reader);
                if (stderrDrainThread != null) {
                    try {
                        stderrDrainThread.interrupt();
                    } catch (Throwable ignored) {
                    }
                }
                if (process != null) {
                    try {
                        process.destroyForcibly();
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (!inputListenerAlive.get()) break;

            try {
                Thread.sleep(retryDelayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            retryDelayMs = Math.min(retryDelayMs * 2, 10_000);
        }
        Log.i(TAG, "input listener thread exited");
    }

    /**
     * 后台抽干一个 InputStream（典型场景：getevent 的 stderr），避免缓冲区满阻塞子进程。
     */
    @NonNull
    private static Thread drainStreamAsync(@NonNull InputStream in,
                                           @NonNull String tag,
                                           @Nullable java.io.ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[512];
            try {
                int n;
                while (!Thread.currentThread().isInterrupted()
                        && (n = in.read(buf)) > 0) {
                    if (sink != null) sink.write(buf, 0, n);
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "[" + tag + "] "
                                + new String(buf, 0, n, StandardCharsets.UTF_8).trim());
                    }
                }
            } catch (IOException ignored) {
                // 进程退出时 read 会抛异常，吞掉就行
            }
        }, "stream-drain-" + tag);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 单行解析：识别 volume-down 按下事件。
     */
    private void handleGeteventLine(@NonNull String line) {
        if (!line.startsWith("/dev/input/")) return;
        if (!line.contains(VOL_DOWN_TYPE_CODE)) return;

        String valueHex = extractTrailingHex(line);
        if (valueHex == null) return;

        int value;
        try {
            value = Integer.parseInt(valueHex, 16);
        } catch (NumberFormatException e) {
            return;
        }

        // value=0 → 抬起；value=1 → 按下；value=2 → 长按 repeat
        if (value == 0) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastVolDownAt > TRIGGER_WINDOW_MS) {
            volDownStreak = 0;
        }
        lastVolDownAt = now;
        volDownStreak++;
        Log.d(TAG, "VOL_DOWN press streak=" + volDownStreak + " (value=" + value + ")");

        if (volDownStreak >= 2) {
            volDownStreak = 0;
            debugLog("trigger", "double-tap detected, triggering pipeline");
            triggerPipeline("getevent_voldown_x2");
        }
    }

    @Nullable
    private static String extractTrailingHex(@NonNull String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        if (end == 0) return null;

        int start = end;
        while (start > 0 && !Character.isWhitespace(line.charAt(start - 1))) {
            start--;
        }
        if (start == end) return null;

        String token = line.substring(start, end);
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return null;
        }
        return token;
    }

    // ------------------------------------------------------------
    // 流水线：截图 → Vision → 呈现
    // ------------------------------------------------------------

    /**
     * 入口：触发一次完整流水线。
     * <p>用 {@link AtomicBoolean#compareAndSet} 保证任何时候只有一个
     * pipeline 在跑，新触发的会被丢弃并 Log.i。
     */
    private void triggerPipeline(@NonNull String source) {
        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "pipeline already running, ignore trigger from " + source);
            return;
        }
        ioHandler.post(() -> {
            try {
                // 1) 截图到 App 私有 cache 目录（自动 mkdir + chmod 666）
                final String[] capturePathHolder = new String[1];
                final boolean[] captureDone = {false};
                RootCmdExecutor.get().captureScreenToAppCacheAsync(
                        this, SCREENSHOT_NAME,
                        r -> {
                            try {
                                if (r.success) {
                                    capturePathHolder[0] =
                                            RootCmdExecutor.resolveAppCachePath(this, SCREENSHOT_NAME);
                                } else {
                                    Log.w(TAG, "screencap failed: " + r);
                                }
                            } finally {
                                synchronized (captureDone) {
                                    captureDone[0] = true;
                                    captureDone.notifyAll();
                                }
                            }
                        });
                synchronized (captureDone) {
                    long deadline = System.currentTimeMillis() + 15_000L;
                    while (!captureDone[0]) {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) break;
                        try {
                            captureDone.wait(left);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                String capturePath = capturePathHolder[0];
                if (capturePath == null || !RootCmdExecutor.fileExists(capturePath)) {
                    Log.w(TAG, "screencap failed or file missing");
                    return;
                }
                debugLog("pipeline", "screenshot ready: " + capturePath);

                // 2) 调 Vision（ConfigStore 由 AiVisionClient 内部读取，永远拿最新）
                String prompt = buildPrompt();
                AiVisionClient.get().sendImageAsync(capturePath, prompt,
                        new AiVisionClient.ResultCallback() {
                            @Override
                            public void onSuccess(@NonNull AiVisionClient.Result r) {
                                // OkHttp 回调在子线程 —— 切回主线程呈现
                                mainHandler.post(() -> presentAnswer(r.content));
                            }

                            @Override
                            public void onFailure(@NonNull Throwable error) {
                                Log.w(TAG, "vision failed: " + error.getMessage());
                                // 失败也走通知（保持用户至少有反馈）
                                mainHandler.post(() ->
                                        showLongAnswer("AI 调用失败: " + error.getMessage()));
                            }
                        });
            } catch (Throwable t) {
                Log.e(TAG, "pipeline error", t);
            } finally {
                busy.set(false);
            }
        });
    }

    /**
     * 答案呈现分流器（v5）。
     * <p>判定规则：
     * <ul>
     *   <li>trim 后长度 ≤ {@value #SHORT_ANSWER_THRESHOLD}（含等号）：
     *       走 {@link #showShortAnswer}（am broadcast 系统 Toast）；</li>
     *   <li>否则：走 {@link #showLongAnswer}（BigTextStyle 通知）。</li>
     * </ul>
     * <p>am broadcast Toast 内部有降级逻辑 —— 失败会自动转通知。
     */
    private void presentAnswer(@NonNull String answer) {
        final String trimmed = answer == null ? "" : answer.trim();
        // 不管长短都先在 logcat + debug log 落盘一份，便于自动化 / 排查
        Log.i(TAG, "AI ANSWER: " + trimmed);
        debugLog("answer", "[" + trimmed.length() + " chars] " + trimmed);

        if (trimmed.isEmpty()) {
            // 空内容：避免发一条空 Toast 触发奇怪行为，直接走通知
            showLongAnswer("(空响应)");
            return;
        }

        if (trimmed.length() <= SHORT_ANSWER_THRESHOLD) {
            showShortAnswer(trimmed);
        } else {
            showLongAnswer(trimmed);
        }
    }

    /**
     * 短答案：系统广播 Toast，失败降级为通知。
     * <p>为什么要切到 ioHandler：am broadcast 走 su，是 IO。
     * 为什么要切回主线程：通知必须主线程。
     */
    private void showShortAnswer(@NonNull String text) {
        // 文本前加 "AI提示: "，方便用户一眼看出来源
        final String toastText = "AI提示: " + text;
        debugLog("toast", "am broadcast Toast: " + toastText);

        // 先在主线程尝试一次 Toast 兜底：万一广播没生效，至少应用层有显示
        // （短答案在主线程直接 Toast 是允许的，因为 Toast.makeText 会切到 Toast 内部线程）
        final Runnable fallbackToNotification = () -> {
            Log.w(TAG, "am broadcast Toast unavailable, fallback to Notification");
            debugLog("toast", "am broadcast failed, fallback to Notification");
            showLongAnswer(text);
        };

        ioHandler.post(() -> {
            try {
                RootCmdExecutor.get().systemToastAsync(toastText, r -> {
                    // am broadcast 回调也在 IO 线程上 —— 切回主线程
                    mainHandler.post(() -> {
                        try {
                            if (r != null && r.success) {
                                Log.i(TAG, "am broadcast Toast ok, exit=" + r.exitCode);
                            } else {
                                Log.w(TAG, "am broadcast Toast failed: " + r);
                                fallbackToNotification.run();
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "systemToast callback crashed", t);
                            fallbackToNotification.run();
                        }
                    });
                });
            } catch (Throwable t) {
                Log.e(TAG, "systemToastAsync dispatch failed", t);
                mainHandler.post(fallbackToNotification);
            }
        });
    }

    /**
     * 长答案：BigTextStyle 通知。必须在主线程调用。
     */
    private void showLongAnswer(@NonNull String text) {
        // 不论被谁调用，最终都切到主线程做 UI（避免某些路径下被 ioHandler 直接调）
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> showLongAnswer(text));
            return;
        }
        try {
            ensureNotificationChannel(this);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                Log.w(TAG, "NotificationManager is null");
                return;
            }

            Intent contentIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = contentIntent != null
                    ? PendingIntent.getActivity(this, 0, contentIntent, piFlags)
                    : null;

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("AI 答案")
                        .setContentText(previewForNotification(text))
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setAutoCancel(true)
                        .setWhen(System.currentTimeMillis());
            } else {
                builder = new Notification.Builder(this)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("AI 答案")
                        .setContentText(previewForNotification(text))
                        .setAutoCancel(true)
                        .setWhen(System.currentTimeMillis());
            }
            if (pi != null) builder.setContentIntent(pi);

            try {
                nm.notify(NOTIF_ID_RESULT, builder.build());
            } catch (Throwable t) {
                Log.w(TAG, "notify failed", t);
            }
        } catch (Throwable t) {
            // 任何异常都不能杀线程
            Log.e(TAG, "showLongAnswer crashed", t);
        }
    }

    private static String previewForNotification(@NonNull String text) {
        if (text.length() <= 80) return text;
        return text.substring(0, 80) + "…";
    }

    @NonNull
    private String buildPrompt() {
        return "你是一个屏幕内容助手。请仔细查看这张截图，" +
                "如果里面是选择题或判断题，只输出最终答案（字母或 True/False），不要解释；" +
                "如果是问答题，给出简洁、可直接抄写的答案；" +
                "如果没有可回答的内容，回复 'NO_QUESTION'。";
    }

    // ------------------------------------------------------------
    // Notification Channel & KeepAlive
    // ------------------------------------------------------------

    private static void ensureNotificationChannel(@NonNull Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Assist Results",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("AI 答题结果");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildKeepAliveNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setContentTitle("Assist 已就绪")
                    .setContentText("500ms 内连按 2 次音量减触发")
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MIN);
        } else {
            builder = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setContentTitle("Assist 已就绪")
                    .setContentText("500ms 内连按 2 次音量减触发")
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MIN);
        }
        return builder.build();
    }

    // ------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------

    private static void closeQuietly(@Nullable java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String safePid(@NonNull Process p) {
        try {
            java.lang.reflect.Method m = Process.class.getMethod("pid");
            Object pid = m.invoke(p);
            return String.valueOf(pid);
        } catch (Throwable t) {
            return "?";
        }
    }
}

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
 * <h2>答案呈现分流（v2 主理人契约）</h2>
 *
 * <ul>
 *   <li><b>拿到 answer 后必须切回主线程</b> —— 直接 {@code new Handler(Looper.getMainLooper()).post(...)}，
 *       Notification / 系统 Toast 都在主线程做；</li>
 *   <li><b>null / trim().isEmpty()</b>：用 NotificationManager 发一条 "未识别到答案" 高优先级
 *       BigTextStyle 通知（独立 channel，IMPORTANCE_HIGH）；</li>
 *   <li><b>trim().length() <= {@value #SHORT_ANSWER_THRESHOLD}</b>：走
 *       {@link RootCmdExecutor#toast(String)} 弹系统广播 Toast —— 不产生应用层 window token，
 *       不会在反作弊 / 录屏工具里出现悬浮窗；</li>
 *   <li><b>其余</b>：用 NotificationManager + BigTextStyle 高优先级通知；</li>
 *   <li><b>绝对不要</b>起任何 WindowManager 悬浮窗 —— 会被反作弊扫到。</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>{@link #ioHandler}（HandlerThread 派发）：截屏、调用 Vision、am broadcast 这些 IO 调度；</li>
 *   <li>{@link #mainHandler}（主线程 Handler）：所有 UI 动作 —— Toast / Notification / Log。</li>
 * </ul>
 */
public class BackgroundAssistService extends Service {

    private static final String TAG = "AssistSvc";

    public static final String ACTION_TRIGGER = "com.xq.xxt.ai.TRIGGER";

    /** 两次音量减之间的最大间隔（ms） */
    private static final long TRIGGER_WINDOW_MS = 500L;

    /**
     * 短答案阈值：trim 后长度 ≤ 该值（包含等号）走系统 Toast。
     * <p>为什么 = 4：A/B/C/D 单字母 + True/False 都恰好 ≤ 4，能完整覆盖
     * 选择题与判断题；超过 4 的基本都是问答题文本，必须用 BigTextStyle。</p>
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
     * 用 {@code "0001 0072"} 做 type+code 定位，再单独判断 value ≠ 0 即按下。
     */
    private static final String VOL_DOWN_TYPE_CODE = "0001 0072";

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

    // ============================================================
    // Service 生命周期
    // ============================================================

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

    // ============================================================
    // /dev/input 监听线程（v3：su -c getevent + raw 协议解析）
    // ============================================================

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

                // 数组形式（不经过 /system/bin/sh -c 的二次解析）
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

    // ============================================================
    // 流水线：截屏 → Vision → 切主线程 → 呈现
    // ============================================================

    /**
     * 入口：触发一次完整流水线。
     * <p>用 {@link AtomicBoolean#compareAndSet} 保证任何时候只有一个
     * pipeline 在跑，新触发的会被丢弃并 Log.i。</p>
     */
    private void triggerPipeline(@NonNull String source) {
        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "pipeline already running, ignore trigger from " + source);
            return;
        }
        ioHandler.post(() -> {
            try {
                // 1) 截屏 —— RootCmdExecutor.screenshot() 是同步实现（内部守护线程跑 su），
                //    不阻塞调用方太久
                java.io.File png = RootCmdExecutor.screenshot(this);
                if (png == null || !png.exists() || png.length() == 0L) {
                    Log.w(TAG, "screencap failed or file missing/empty");
                    // 主线程呈现失败通知
                    mainHandler.post(() -> showLongAnswer("截图失败"));
                    return;
                }
                debugLog("pipeline", "screenshot ready: " + png.getAbsolutePath()
                        + " (" + png.length() + " bytes)");

                // 2) 调 Vision（一次性 Callback，不复用旧 ResultCallback）
                AiVisionClient.get().analyze(png, new AiVisionClient.Callback() {
                    @Override
                    public void onSuccess(@NonNull String answer) {
                        // OkHttp 回调在子线程 —— 切回主线程呈现
                        mainHandler.post(() -> presentAnswer(answer));
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
                mainHandler.post(() -> showLongAnswer("内部异常: " + t.getClass().getSimpleName()));
            } finally {
                busy.set(false);
            }
        });
    }

    /**
     * 答案呈现分流器（v2 主理人契约）。
     * <p><b>调用约定：</b>本方法必须在主线程上调用（pipeline 在 OkHttp 回调里通过
     * {@link #mainHandler}.post 已经切回主线程，调用方无需再切）。</p>
     * <p>判定规则：</p>
     * <ul>
     *   <li>answer == null 或 trim().isEmpty() → "未识别到答案" 通知（高优先级 BigTextStyle）；</li>
     *   <li>trim().length() <= {@value #SHORT_ANSWER_THRESHOLD} →
     *       {@link RootCmdExecutor#toast(String)} 弹系统广播 Toast（不产生悬浮窗）；</li>
     *   <li>其余 → BigTextStyle 通知（高优先级）。</li>
     * </ul>
     */
    private void presentAnswer(@Nullable String answer) {
        // 二次保险：如果在主线程以外被调到，自动切回主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final String a = answer;
            mainHandler.post(() -> presentAnswer(a));
            return;
        }

        final String trimmed = answer == null ? "" : answer.trim();
        // 不管长短都先在 logcat + debug log 落盘一份，便于自动化 / 排查
        Log.i(TAG, "AI ANSWER: " + trimmed);
        debugLog("answer", "[" + trimmed.length() + " chars] " + trimmed);

        if (trimmed.isEmpty()) {
            // null 或空 → "未识别到答案" 高优先级通知
            showLongAnswer("未识别到答案");
            return;
        }

        if (trimmed.length() <= SHORT_ANSWER_THRESHOLD) {
            showShortAnswer(trimmed);
        } else {
            showLongAnswer(trimmed);
        }
    }

    /**
     * 短答案：系统广播 Toast。
     * <p><b>为什么不在主线程直接 Toast.makeText(...).show()：</b>那种方式会创建应用层
     * window token，在某些反作弊 / 录屏检测下会扫到悬浮窗。改走
     * {@code RootCmdExecutor.toast(text)} = {@code am broadcast}，由系统 ToastService
     * 在系统窗口里展示，对应用完全透明。</p>
     * <p><b>为什么用 ioHandler：</b>{@code am broadcast} 走 su，是 IO；放回 IO 线程
     * 不卡主线程。</p>
     */
    private void showShortAnswer(@NonNull String text) {
        // 文本前加 "AI提示: "，方便用户一眼看出来源
        final String toastText = "AI提示: " + text;
        debugLog("toast", "am broadcast Toast: " + toastText);
        Log.i(TAG, "AI提示: " + toastText);

        ioHandler.post(() -> {
            try {
                RootCmdExecutor.Result r = RootCmdExecutor.toast(toastText);
                if (r == null || !r.success) {
                    Log.w(TAG, "am broadcast Toast failed: " + r);
                    debugLog("toast", "am broadcast failed, fallback to Notification");
                    mainHandler.post(() -> showLongAnswer(text));
                }
            } catch (Throwable t) {
                Log.e(TAG, "toast dispatch failed", t);
                mainHandler.post(() -> showLongAnswer(text));
            }
        });
    }

    /**
     * 长答案 / 失败 / 空答案：BigTextStyle 通知。必须在主线程调用。
     * <p><b>为什么走 NotificationManager + BigTextStyle：</b>长文本用系统 Toast 会被截断
     * （部分 ROM 限制 200 字符以内），而通知的 BigTextStyle 没有这种限制。</p>
     */
    private void showLongAnswer(@NonNull String text) {
        // 二次保险
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

    // ============================================================
    // Notification Channel & KeepAlive
    // ============================================================

    /**
     * 创建 NotificationChannel（API 26+）。本项目 channelId = {@value #CHANNEL_ID}，
     * 单独建一个 <b>IMPORTANCE_HIGH</b> 的 channel —— 这样答案通知可以走 heads-up，
     * 用户在锁屏也能立刻看到，不会因为重要性太低被系统压住。
     */
    private static void ensureNotificationChannel(@NonNull Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Assist Results",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("AI 答题结果（高优先级，锁屏可见）");
        channel.setShowBadge(false);
        // 关键：允许绕过勿扰（部分 ROM 上要显式开启才能 heads-up）
        try {
            channel.setBypassDnd(false);
        } catch (Throwable ignored) {
        }
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

    // ============================================================
    // 辅助
    // ============================================================

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

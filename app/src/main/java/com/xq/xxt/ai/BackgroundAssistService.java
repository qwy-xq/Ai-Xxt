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
 * 通过 {@code Runtime.exec(new String[]{"su", "-c", "getevent -q"})} 启动一个
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
 *       {@code su -c "..."} 让命令绑定到 su 的整个生命周期。</li>
 *   <li>不再依赖 {@code getevent -l} 输出标签名 —— ColorOS / 部分 ROM 的
 *       toybox getevent 在 root 进程下输出格式不稳。直接用 raw 协议解析，
 *       即 {@code " 0001 0072 ..."} 这种固定格式（hex 形式 / 短形式都覆盖）。</li>
 * </ol>
 *
 * <p>全局监听 {@code /dev/input/event*}（不给 getevent 传具体设备路径），
 * 因此不锁死具体 event 节点，适配一加 Pad Pro 上节点编号变化的情况。</p>
 *
 * <p>触发手势：500ms 内连续按 2 次音量减（type=0x0001, code=0x0072, value≠0）。</p>
 */
public class BackgroundAssistService extends Service {

    private static final String TAG = "AssistSvc";

    public static final String ACTION_TRIGGER = "com.xq.xxt.ai.TRIGGER";

    /** 两次音量减之间的最大间隔（ms） */
    private static final long TRIGGER_WINDOW_MS = 500L;

    /** 短答案阈值：超过这个长度走通知栏 */
    private static final int SHORT_ANSWER_THRESHOLD = 60;

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
     * 短形式 value 会被打印成 1 / 0 / 2（2=repeat）。
     * 这里用 {@code VOL_DOWN_TYPE_CODE} = "0001 0072"} 做 type+code 定位，
     * 再单独判断 value ≠ 0 即按下动作。
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
        try {
            String line = "[" + new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date()) + "] [" + tag + "] " + msg + "\n";
            try (java.io.FileWriter fw = new java.io.FileWriter(DEBUG_LOG_FILE, true);
                 java.io.BufferedWriter bw = new java.io.BufferedWriter(fw)) {
                bw.write(line);
            }
        } catch (Throwable ignored) {
            // 调试日志写失败不能影响主流程
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

        // 启动 /dev/input 监听线程
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

    /**
     * 启动常驻线程。
     * <p>线程主体在 {@link #inputListenerLoop()} 里：
     * <ol>
     *   <li>{@code Runtime.exec(new String[]{"su", "-c", "getevent -q"})} 启动 root 子进程；</li>
     *   <li>{@code su} 接管命令后挂住不退出，{@code getevent} 进入常驻监听；</li>
     *   <li>父进程从子进程 stdout 按行读取 raw input_event；</li>
     *   <li>命中音量减按下事件 → 累计连击，达到 2 次立刻触发流水线。</li>
     * </ol>
     */
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

                // 注意：使用数组形式（不会经过 /system/bin/sh -c 的二次解析）
                // 这样 getevent 的所有参数都直达 su shell。
                process = Runtime.getRuntime().exec(new String[]{
                        "su",
                        "-c",
                        "getevent -q 2>&1"
                });
                geteventProcess = process;
                debugLog("ge", "spawned pid=" + (process != null ? safePid(process) : "null"));

                // 单独起一个线程把 stderr 抽干，防止管道缓冲区满卡住 getevent。
                // 同时把内容写到 stderrBuf，结束后 dump 到 debug log 方便诊断。
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
                debugLog("ge-err-stderr", stderrBuf.toString("UTF-8"));
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
    private static Thread drainStreamAsync(@NonNull InputStream in, @NonNull String tag,
                                          @Nullable java.io.ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[512];
            try {
                int n;
                while (!Thread.currentThread().isInterrupted()
                        && (n = in.read(buf)) > 0) {
                    if (sink != null) sink.write(buf, 0, n);
                    // 选择性记录：getevent 偶尔会向 stderr 喷 warning，不影响主逻辑
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "[" + tag + "] " + new String(buf, 0, n, StandardCharsets.UTF_8).trim());
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
     *
     * <p>getevent raw 输出示例（不含 -l 标签、含 -q 静默）：</p>
     * <pre>
     *   add device 1: /dev/input/event4
     *   /dev/input/event4: 0001 0072 00000001   ← 按下
     *   /dev/input/event4: 0001 0072 00000000   ← 抬起
     *   /dev/input/event5: 0001 0072 00000002   ← 长按 repeat
     * </pre>
     *
     * <p>判定条件（不做设备节点白名单，event* 都接）：</p>
     * <ul>
     *   <li>行首必须是 {@code /dev/input/} 开头（过滤掉 "add device" 这种启动行）；</li>
     *   <li>行内包含 {@code " 0001 0072"}（type=EV_KEY, code=KEY_VOLUMEDOWN）；</li>
     *   <li>value ≠ 0：按下（1）或长按 repeat（2），抬起（0）忽略。</li>
     * </ul>
     */
    private void handleGeteventLine(@NonNull String line) {
        // 1. 必须是 /dev/input/event* 开头的事件行，过滤掉 "add device" 这种元信息
        if (!line.startsWith("/dev/input/")) return;

        // 2. 必须包含 type+code 关键字
        if (!line.contains(VOL_DOWN_TYPE_CODE)) return;

        // 3. 提取 value（行尾最后一个 hex token）
        String valueHex = extractTrailingHex(line);
        if (valueHex == null) return;

        int value;
        try {
            value = Integer.parseInt(valueHex, 16);
        } catch (NumberFormatException e) {
            return;
        }

        // value=0 → 抬起；value=1 → 按下；value=2 → 长按 repeat
        // 仅按下/长按触发连击计数，抬起忽略。
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

    /**
     * 从 getevent 输出行里提取行尾的十六进制 token。
     * <p>支持格式：</p>
     * <ul>
     *   <li>{@code "... 0001 0072 00000001"} → 返回 {@code "00000001"}</li>
     *   <li>{@code "... 0001 0072 1"}         → 返回 {@code "1"}（短形式）</li>
     * </ul>
     *
     * @return 十六进制字符串（不带 0x 前缀）；解析失败返回 null
     */
    @Nullable
    private static String extractTrailingHex(@NonNull String line) {
        // 去掉尾部空白
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        if (end == 0) return null;

        // 从 end 往前找到第一个空白（token 分隔符）
        int start = end;
        while (start > 0 && !Character.isWhitespace(line.charAt(start - 1))) {
            start--;
        }
        if (start == end) return null;

        String token = line.substring(start, end);
        // 必须是纯十六进制字符
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
                            if (r.success) {
                                capturePathHolder[0] =
                                        RootCmdExecutor.resolveAppCachePath(this, SCREENSHOT_NAME);
                            }
                            synchronized (captureDone) {
                                captureDone[0] = true;
                                captureDone.notifyAll();
                            }
                        });
                synchronized (captureDone) {
                    long deadline = System.currentTimeMillis() + 15_000L;
                    while (!captureDone[0]) {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) break;
                        captureDone.wait(left);
                    }
                }

                String capturePath = capturePathHolder[0];
                if (capturePath == null || !RootCmdExecutor.fileExists(capturePath)) {
                    Log.w(TAG, "screencap failed or file missing");
                    return;
                }

                // 2) 调 Vision（ConfigStore 由 AiVisionClient 内部读取，永远拿最新）
                String prompt = buildPrompt();
                AiVisionClient.get().sendImageAsync(capturePath, prompt,
                        new AiVisionClient.ResultCallback() {
                            @Override
                            public void onSuccess(@NonNull AiVisionClient.Result r) {
                                presentAnswer(r.content);
                            }

                            @Override
                            public void onFailure(@NonNull Throwable error) {
                                Log.w(TAG, "vision failed: " + error.getMessage());
                                showLongAnswer("AI 调用失败: " + error.getMessage());
                            }
                        });
            } catch (Throwable t) {
                Log.e(TAG, "pipeline error", t);
            } finally {
                busy.set(false);
            }
        });
    }

    private void presentAnswer(@NonNull String answer) {
        String trimmed = answer.trim();
        if (trimmed.length() <= SHORT_ANSWER_THRESHOLD && !trimmed.contains("\n")) {
            RootCmdExecutor.get().systemToastAsync("AI答案: " + trimmed, null);
        } else {
            showLongAnswer(trimmed);
        }
    }

    private void showLongAnswer(@NonNull String text) {
        ensureNotificationChannel(this);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

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
            return String.valueOf(p.pid());
        } catch (Throwable t) {
            return "?";
        }
    }
}
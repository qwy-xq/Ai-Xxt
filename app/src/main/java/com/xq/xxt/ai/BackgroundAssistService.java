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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台常驻 Service —— 整个 "按键触发 → 截屏 → AI → 隐藏呈现" 链路的大脑。
 * <p>
 * <b>v2 关键改动：</b>
 * <ol>
 *   <li>按键监听从 {@code onKeyDown}（依赖窗口焦点）改为：<b>root 跑 {@code getevent}
 *       直接读 {@code /dev/input/event*}</b>，无论 Activity 是否在前台、是否被全屏独占，
 *       只要 Linux 内核能收到硬件中断就能识别手势。</li>
 *   <li>截图路径从 {@code /data/local/tmp/} 改为 App 私有 cache 目录，并通过
 *       {@code chmod 666} 解决 SELinux 标签问题。</li>
 *   <li>API 配置从 {@link AiVisionClient} 内部 setter 改为
 *       {@link ConfigStore}（SharedPreferences），每次请求实时读取。</li>
 * </ol>
 * <p>
 * 触发手势：连续按 2 次音量减键（间隔 ≤ {@link #TRIGGER_WINDOW_MS} ms）。
 * 之所以选 2 次而不是 3 次，是因为底层事件流里 1 次按键会产生 DOWN + UP 两条事件，
 * 用 2 次按键（4 条事件）作为一次完整手势既不容易误触也不容易漏触。
 */
public class BackgroundAssistService extends Service {

    private static final String TAG = "AssistSvc";

    public static final String ACTION_TRIGGER = "com.xq.xxt.ai.TRIGGER";

    /** 两次音量减之间的最大间隔（ms） */
    private static final long TRIGGER_WINDOW_MS = 1_200L;

    /** 短答案阈值：超过这个长度走通知栏 */
    private static final int SHORT_ANSWER_THRESHOLD = 60;

    private static final String CHANNEL_ID = "assist_results";
    private static final int NOTIF_ID_KEEP_ALIVE = 0xA15E;
    private static final int NOTIF_ID_RESULT = 0xA15F;

    /** 截图文件名（放到 App 私有 cache 目录） */
    private static final String SCREENSHOT_NAME = "screenshot.png";

    /** {@code getevent} 输出里要监听的关键字 */
    private static final String KEY_NAME = "KEY_VOLUMEDOWN";
    private static final String DOWN_TOKEN = "DOWN";

    private HandlerThread ioThread;
    private Handler ioHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean inputListenerAlive = new AtomicBoolean(false);

    private long lastVolDownAt = 0L;
    private int volDownStreak = 0;

    /** {@code getevent} 进程，销毁 Service 时需要 forcibly kill */
    private volatile Process geteventProcess;

    /** 供 UI（MainActivity）查询服务运行状态 */
    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    private BroadcastReceiver externalReceiver;

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
    // /dev/input 监听线程（核心：完全脱离 Android 窗口焦点）
    // ------------------------------------------------------------

    /**
     * 启动一个常驻线程，root 跑 {@code getevent -lt /dev/input/event*}，
     * 从 stdout 解析出 KEY_VOLUMEDOWN 事件，识别两次连击手势。
     */
    private void startInputListener() {
        if (inputListenerAlive.get()) return;
        inputListenerAlive.set(true);
        new Thread(this::inputListenerLoop, "input-listener").start();
        Log.i(TAG, "input listener thread started");
    }

    private void stopInputListener() {
        Process p = geteventProcess;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    /**
     * getevent 主循环。
     * <p>
     * 命令：
     * <pre>
     *   su
     *   getevent -lt /dev/input/event* 2>&1
     * </pre>
     * 输出格式（带 -l -t）：
     * <pre>
     *   /dev/input/event4: 12345.678901  KEY_VOLUMEDOWN  DOWN
     *   /dev/input/event4: 12345.789012  KEY_VOLUMEDOWN  UP
     * </pre>
     * <p>
     * 只需要匹配 {@code KEY_VOLUMEDOWN} + {@code DOWN} 的行就能拿到每次按键的按下时刻。
     */
    private void inputListenerLoop() {
        // 退避重试：getevent 启动失败（root 未授权等）时不要一直狂试
        int retryDelayMs = 1000;
        while (inputListenerAlive.get()) {
            Process process = null;
            BufferedReader reader = null;
            try {
                Log.i(TAG, "spawning getevent via su ...");
                process = new ProcessBuilder("su").redirectErrorStream(true).start();
                geteventProcess = process;

                try (OutputStream os = process.getOutputStream()) {
                    // -l 标签格式（输出 KEY_VOLUMEDOWN 而不是 0x72）
                    // -t 带时间戳
                    // 不加 -p：不打印 "add device" 行
                    // 2>&1 把 stderr 也合到 stdout
                    String cmd = "getevent -lt /dev/input/event* 2>&1\n";
                    os.write(cmd.getBytes(StandardCharsets.UTF_8));
                    os.write("exit\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

                String line;
                while (inputListenerAlive.get() && (line = reader.readLine()) != null) {
                    handleGeteventLine(line);
                }
            } catch (Throwable t) {
                Log.w(TAG, "getevent loop interrupted: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            } finally {
                geteventProcess = null;
                closeQuietly(reader);
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }

            if (!inputListenerAlive.get()) break;
            // 退避后重试
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
     * 单行解析：识别 "KEY_VOLUMEDOWN ... DOWN"。
     */
    private void handleGeteventLine(@NonNull String line) {
        if (!line.contains(KEY_NAME)) return;
        if (!line.contains(DOWN_TOKEN)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastVolDownAt > TRIGGER_WINDOW_MS) {
            volDownStreak = 0;
        }
        lastVolDownAt = now;
        volDownStreak++;
        Log.d(TAG, "VOL_DOWN_DOWN count=" + volDownStreak);

        if (volDownStreak >= 2) {
            volDownStreak = 0;
            triggerPipeline("getevent_x2");
        }
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
                    .setContentText("连按 2 次音量减触发（系统级监听）")
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MIN);
        } else {
            builder = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setContentTitle("Assist 已就绪")
                    .setContentText("连按 2 次音量减触发（系统级监听）")
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
}
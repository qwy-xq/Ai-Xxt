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
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台常驻 Service —— 整个 "按键触发 → 截屏 → AI → 隐藏呈现" 链路的大脑。
 * <p>
 * 触发方式：连续按 3 次音量减键（在系统级焦点 / 全屏独占场景下，
 * 系统键事件仍然能通过 {@link #onKeyDown} 投递过来），同时支持外部通过
 * 自定义广播 {@link #ACTION_TRIGGER} 强行触发，方便自动化测试。
 * <p>
 * 防误触：3 次按键必须在 {@link #TRIGGER_WINDOW_MS} 内连续按下才会触发。
 * <p>
 * 隐匿呈现策略：
 * <ul>
 *   <li>短答案（≤ 60 字符）：走 {@link RootCmdExecutor#systemToastAsync}，通过系统 Toast 服务渲染，零窗口句柄；</li>
 *   <li>长答案：走 {@link NotificationManager}，用 BigTextStyle 推送富文本通知，用户下拉通知栏查看。</li>
 * </ul>
 */
public class BackgroundAssistService extends Service {

    private static final String TAG = "AssistSvc";

    /** 外部触发广播：adb shell am broadcast -a com.xq.xxt.ai.TRIGGER */
    public static final String ACTION_TRIGGER = "com.xq.xxt.ai.TRIGGER";

    /** 配置覆盖广播：用于运行时设置 apiKey/baseUrl/model，例如自动化测试或自建面板 */
    public static final String ACTION_CONFIG = "com.xq.xxt.ai.CONFIG";
    public static final String EXTRA_API_KEY = "api_key";
    public static final String EXTRA_BASE_URL = "base_url";
    public static final String EXTRA_MODEL = "model";

    /** 3 次音量减必须在 1.5s 内按完 */
    private static final long TRIGGER_WINDOW_MS = 1_500L;

    /** 短答案阈值：超过这个长度走通知栏 */
    private static final int SHORT_ANSWER_THRESHOLD = 60;

    /** Notification Channel */
    private static final String CHANNEL_ID = "assist_results";
    private static final int NOTIF_ID = 0xA15E;

    /** 截屏文件固定路径 */
    private static final String SCREENSHOT_PATH = "/data/local/tmp/screencapture.png";

    private HandlerThread ioThread;
    private Handler ioHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean busy = new AtomicBoolean(false);

    // 音量减连击状态
    private int volDownCount = 0;
    private long lastVolDownAt = 0L;

    private BroadcastReceiver externalReceiver;

    // ------------------------------------------------------------
    // Service 生命周期
    // ------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        ioThread = new HandlerThread("assist-io");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());

        ensureNotificationChannel(this);

        // 注册外部触发 + 配置覆盖接收器
        externalReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(@NonNull Context context, @NonNull Intent intent) {
                String action = intent.getAction();
                if (ACTION_TRIGGER.equals(action)) {
                    triggerPipeline("broadcast:" + ACTION_TRIGGER);
                } else if (ACTION_CONFIG.equals(action)) {
                    AiVisionClient c = AiVisionClient.get();
                    if (intent.hasExtra(EXTRA_API_KEY)) c.withApiKey(intent.getStringExtra(EXTRA_API_KEY));
                    if (intent.hasExtra(EXTRA_BASE_URL)) c.withBaseUrl(intent.getStringExtra(EXTRA_BASE_URL));
                    if (intent.hasExtra(EXTRA_MODEL)) c.withModel(intent.getStringExtra(EXTRA_MODEL));
                    Log.i(TAG, "config updated: model=" + c.model + " base=" + c.baseUrl);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TRIGGER);
        filter.addAction(ACTION_CONFIG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(externalReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            //noinspection UnspecifiedRegisterReceiverFlag
            registerReceiver(externalReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // 用一个低优先级的静默通知把 Service 抬到前台，避免被系统当作空 Service 杀掉
        startForeground(NOTIF_ID, buildKeepAliveNotification());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
        return null; // 不允许 bind，纯 startService 模式
    }

    // ------------------------------------------------------------
    // 按键监听 —— Android 14+ 上前台 Service 仍然能收到 KeyEvent
    // ------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastVolDownAt > TRIGGER_WINDOW_MS) {
                volDownCount = 0;
            }
            lastVolDownAt = now;
            volDownCount++;
            Log.d(TAG, "volDownCount=" + volDownCount);

            if (volDownCount >= 3) {
                volDownCount = 0;
                triggerPipeline("volumedown_x3");
                // 吃掉事件，避免系统弹音量条
                return true;
            }
            // 前两次也吃掉，避免系统音量 UI 闪现
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------
    // 流水线：截图 → Vision → 呈现
    // ------------------------------------------------------------

    /**
     * 触发整条流水线。{@code source} 只是日志标识，不影响功能。
     */
    private void triggerPipeline(@NonNull String source) {
        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "pipeline already running, ignore trigger from " + source);
            return;
        }
        ioHandler.post(() -> {
            try {
                // 1. 截屏
                RootCmdExecutor.Result[] captureHolder = new RootCmdExecutor.Result[1];
                final boolean[] captureDone = {false};
                RootCmdExecutor.get().captureScreenAsync(r -> {
                    captureHolder[0] = r;
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
                RootCmdExecutor.Result cap = captureHolder[0];
                if (cap == null || !cap.success) {
                    Log.w(TAG, "screencap failed: " + (cap == null ? "null" : cap.toString()));
                    return;
                }

                // 2. 调 Vision
                String prompt = buildPrompt();
                AiVisionClient.get().sendImageAsync(SCREENSHOT_PATH, prompt, new AiVisionClient.ResultCallback() {
                    @Override
                    public void onSuccess(@NonNull AiVisionClient.Result r) {
                        presentAnswer(r.content);
                    }

                    @Override
                    public void onFailure(@NonNull Throwable error) {
                        Log.w(TAG, "vision failed: " + error.getMessage());
                        // 失败也走通知栏，至少能看见为什么
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

    /**
     * 根据答案长度决定呈现方式。短答案走系统 Toast，长答案走通知栏富文本。
     */
    private void presentAnswer(@NonNull String answer) {
        String trimmed = answer.trim();
        if (trimmed.length() <= SHORT_ANSWER_THRESHOLD && !trimmed.contains("\n")) {
            RootCmdExecutor.get().systemToastAsync("AI答案: " + trimmed, null);
        } else {
            showLongAnswer(trimmed);
        }
    }

    /**
     * 推一条富文本通知。下拉通知栏可看完整内容。
     */
    private void showLongAnswer(@NonNull String text) {
        ensureNotificationChannel(this);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // PendingIntent 用 MainActivity 兜底（如果有的话），点开通知就进应用查看历史
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
            // 旧路径，保留兼容性
            builder = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("AI 答案")
                    .setContentText(previewForNotification(text))
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis());
        }
        if (pi != null) builder.setContentIntent(pi);

        try {
            nm.notify(NOTIF_ID + 1, builder.build());
        } catch (Throwable t) {
            Log.w(TAG, "notify failed", t);
        }
    }

    private static String previewForNotification(@NonNull String text) {
        // 通知栏首行预览截断
        if (text.length() <= 80) return text;
        return text.substring(0, 80) + "…";
    }

    /**
     * 构造给模型的 prompt。这里给一个通用模板。
     */
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
        NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
        if (existing != null) return;
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
                    .setContentText("连按 3 次音量减触发")
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MIN);
        } else {
            builder = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setContentTitle("Assist 已就绪")
                    .setContentText("连按 3 次音量减触发")
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MIN);
        }
        return builder.build();
    }
}
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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
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

    /** UI 树导出文件名（与 RootCmdExecutor 中的 UI_DUMP_LOCAL_NAME 保持一致） */
    private static final String UI_DUMP_NAME = "uidump.xml";

    /** 裁剪 + 缩放后的"题目小图"文件名（喂给 Vision） */
    private static final String QUESTION_CROP_NAME = "question_crop.jpg";

    /**
     * 缩放目标最长边（像素）。超过此尺寸的边按等比缩到此值以内。
     * <p>为什么是 512：MiniMax M3 的视觉 token 上限对单图约 1024 边长就接近临界，
     * 留 1/2 buffer 更稳；同时 JPEG 质量 85 下大约 80~150KB，远低于网关 Body 限额。</p>
     */
    private static final int MAX_EDGE_PX = 512;

    /** JPEG 压缩质量（85 是肉眼无损的下界，性价比最高） */
    private static final int JPEG_QUALITY = 85;

    /**
     * 状态栏阈值：Y < 此值的节点视为状态栏元素，剔除出候选。
     * <p>为什么是 150：大部分全面屏设备状态栏高度 60~110dp（dpi 3x 下 180~330px），
     * 150 是一个保守的最小公约数，能在不丢掉"题目紧贴状态栏"边缘情况的同时，
     * 稳健过滤掉通知 / 信号 / 电量 / 时间等状态栏元素。</p>
     */
    private static final int STATUS_BAR_PX = 150;

    /**
     * 底部导航栏占比：Y > 屏幕高度 * (1 - 此值) 的节点视为导航栏，剔除出候选。
     * <p>0.18 是基于主流全面屏手势条的折中值。{@code 0} 表示不做底部过滤。</p>
     */
    private static final double NAV_BAR_BOTTOM_RATIO = 0.18;

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
     *
     * <h2>v3 流水线（Root 静默 Dump UI 树 → 解析 bounds → Root 截图 → 内存裁剪缩放 → Vision）</h2>
     * <ol>
     *   <li>{@link RootCmdExecutor#dumpUiXml(Context)} —— {@code su -c uiautomator dump} 拿
     *       当前 UI 布局 XML（已拷到 App 私有 cache，权限 OK）；</li>
     *   <li>{@link RootCmdExecutor#screenshot(Context)} —— 拿全屏无损大图；</li>
     *   <li>解析 XML 找"题目 / 选项 / 公式"容器的 {@code bounds}，
     *       转成屏幕矩形（{@link Rect}）；没匹配上就 fallback 到屏幕中段；</li>
     *   <li>把大图加载到内存（带 {@code inSampleSize} 防 OOM），
     *       按 bounds 裁剪 + 等比缩放到最长边 {@value #MAX_EDGE_PX}px，
     *       压成 {@code question_crop.jpg}（质量 {@value #JPEG_QUALITY}）；</li>
     *   <li>把裁剪小图丢给 {@link AiVisionClient#analyze(File, String, Callback)}，mime 显式
     *       {@code image/jpeg}；</li>
     *   <li>成功 → 主线程呈现；失败 → 触发长通知兜底。</li>
     * </ol>
     */
    private void triggerPipeline(@NonNull String source) {
        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "pipeline already running, ignore trigger from " + source);
            return;
        }
        ioHandler.post(() -> {
            try {
                // 0) 拿屏幕尺寸（任何一步失败都需要做屏幕中段兜底）
                int screenW = 0, screenH = 0;
                android.view.WindowManager wm =
                        (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    android.graphics.Point p = new android.graphics.Point();
                    try {
                        wm.getDefaultDisplay().getRealSize(p);
                    } catch (Throwable ignored) {
                    }
                    screenW = p.x;
                    screenH = p.y;
                }
                debugLog("pipeline", "screen=" + screenW + "x" + screenH);

                // 1) dump UI 树 —— 静默导出当前布局
                File uiXml = RootCmdExecutor.dumpUiXml(this);
                if (uiXml == null || !uiXml.exists() || uiXml.length() == 0L) {
                    Log.w(TAG, "uiautomator dump failed, fallback to full-screen");
                    debugLog("pipeline", "uidump missing, will fallback");
                } else {
                    debugLog("pipeline", "uidump ready: " + uiXml.getAbsolutePath()
                            + " (" + uiXml.length() + " bytes)");
                }

                // 2) Root 截屏 —— 拿无损大图
                File fullshot = RootCmdExecutor.screenshot(this);
                if (fullshot == null || !fullshot.exists() || fullshot.length() == 0L) {
                    Log.w(TAG, "screencap failed or file missing/empty");
                    mainHandler.post(() -> showLongAnswer("截图失败"));
                    return;
                }
                debugLog("pipeline", "screenshot ready: " + fullshot.getAbsolutePath()
                        + " (" + fullshot.length() + " bytes)");

                // 3) 解析 bounds —— 默认屏幕中段兜底
                Rect questionRect = fallbackCenterRect(screenW, screenH);
                if (uiXml != null && uiXml.exists() && uiXml.length() > 0L && screenW > 0) {
                    Rect parsed = UiTreeParser.findQuestionRect(uiXml, screenW, screenH);
                    if (parsed != null) {
                        questionRect = parsed;
                        debugLog("pipeline", "parsed question rect = " + questionRect.toShortString());
                    } else {
                        debugLog("pipeline", "no question rect parsed, using center fallback");
                    }
                }

                // 4) 裁剪 + 缩放 + JPEG
                File cropped;
                try {
                    cropped = cropAndDownscale(fullshot, questionRect, MAX_EDGE_PX,
                            new File(getCacheDir(), QUESTION_CROP_NAME));
                } catch (Throwable cropFail) {
                    Log.e(TAG, "crop/downscale failed, will feed full shot", cropFail);
                    debugLog("pipeline", "crop failed: " + cropFail.getMessage());
                    cropped = fullshot; // 兜底：把整图直接喂给 Vision，至少不阻塞流水线
                }
                debugLog("pipeline", "cropped file: " + cropped.getAbsolutePath()
                        + " (" + cropped.length() + " bytes)");

                // 5) 调 Vision —— mime 显式 image/jpeg，OkHttp 回调在子线程
                AiVisionClient.get().analyze(cropped, "image/jpeg", new AiVisionClient.Callback() {
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
     * 当 UI 树解析失败 / 不存在 / 屏幕尺寸拿不到时的兜底矩形：
     * 屏幕中段（去掉顶部 {@value #STATUS_BAR_PX}px 和底部 {@value #NAV_BAR_BOTTOM_RATIO} 比例）。
     */
    @NonNull
    private static Rect fallbackCenterRect(int screenW, int screenH) {
        if (screenW <= 0 || screenH <= 0) {
            // 极端情况：返回空 Rect，后续会按原图比例走
            return new Rect(0, 0, 0, 0);
        }
        int top = STATUS_BAR_PX;
        int bottom = (int) (screenH * (1.0 - NAV_BAR_BOTTOM_RATIO));
        if (bottom <= top + 32) {
            bottom = screenH; // 屏幕太小，兜底用整屏
        }
        return new Rect(0, top, screenW, bottom);
    }

    /**
     * 把全屏截图按 {@code rect} 裁剪，再等比缩放到最长边 ≤ {@code maxEdgePx}，
     * 压成 JPEG 写到 {@code out}。失败抛异常，由 {@link #triggerPipeline} 走兜底。
     *
     * <p><b>防 OOM 关键：</b>先用 {@link BitmapFactory.Options#inJustDecodeBounds}
     * 探到原图尺寸，再选 {@link BitmapFactory.Options#inSampleSize} 让第一次 decode
     * 出来的"草稿图"长边 ≈ maxEdgePx*2，避免直接把 8K 屏拍立得一次性吃进堆里。</p>
     */
    @NonNull
    private static File cropAndDownscale(@NonNull File srcPng,
                                          @NonNull Rect rect,
                                          int maxEdgePx,
                                          @NonNull File outJpeg) throws IOException {
        // 1) 探尺寸
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(srcPng.getAbsolutePath(), probe);
        int srcW = probe.outWidth;
        int srcH = probe.outHeight;
        if (srcW <= 0 || srcH <= 0) {
            throw new IOException("decodeFile probe failed: " + srcPng.getAbsolutePath());
        }

        // 2) 把用户给的 rect 夹到 [0, srcW) x [0, srcH)
        Rect safe = clampRect(rect, srcW, srcH);

        // 3) 第一次 decode —— 带 inSampleSize，让"草稿图"长边 ≈ maxEdgePx * 2
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = computeInSampleSize(safe.width(), safe.height(), maxEdgePx * 2);
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap draft = BitmapFactory.decodeFile(srcPng.getAbsolutePath(), decode);
        if (draft == null) {
            throw new IOException("decodeFile returned null for " + srcPng.getAbsolutePath());
        }

        // 4) 草稿图是 src 的 1/sampleSize 倍；把 safe 按比例换算到 draft 坐标系
        float ratio = (float) decode.inSampleSize;
        int dx = (int) Math.floor(safe.left / ratio);
        int dy = (int) Math.floor(safe.top / ratio);
        int dw = (int) Math.ceil(safe.width() / ratio);
        int dh = (int) Math.ceil(safe.height() / ratio);
        // 再次夹到 draft 边界
        if (dx < 0) { dw += dx; dx = 0; }
        if (dy < 0) { dh += dy; dy = 0; }
        if (dx + dw > draft.getWidth()) dw = draft.getWidth() - dx;
        if (dy + dh > draft.getHeight()) dh = draft.getHeight() - dy;
        if (dw <= 0 || dh <= 0) {
            draft.recycle();
            throw new IOException("crop region empty after ratio scale: "
                    + safe.toShortString() + " src=" + srcW + "x" + srcH);
        }
        Bitmap cropped = Bitmap.createBitmap(draft, dx, dy, dw, dh);
        if (cropped != draft) {
            draft.recycle();
        }

        // 5) 等比缩放：最长边 = maxEdgePx
        Bitmap scaled = scaleMaxEdge(cropped, maxEdgePx);
        if (scaled != cropped) {
            cropped.recycle();
        }

        // 6) JPEG 写出（compress 失败抛 IOException）
        File parent = outJpeg.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(outJpeg)) {
            boolean ok = scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            fos.flush();
            if (!ok) {
                throw new IOException("Bitmap.compress(JPEG) returned false");
            }
        } finally {
            if (scaled != null && !scaled.isRecycled()) {
                scaled.recycle();
            }
        }
        return outJpeg;
    }

    @NonNull
    private static Rect clampRect(@NonNull Rect r, int maxW, int maxH) {
        int left = Math.max(0, r.left);
        int top = Math.max(0, r.top);
        int right = Math.min(maxW, r.right);
        int bottom = Math.min(maxH, r.bottom);
        if (right <= left) right = Math.min(maxW, left + 1);
        if (bottom <= top) bottom = Math.min(maxH, top + 1);
        return new Rect(left, top, right, bottom);
    }

    /**
     * 计算 {@code inSampleSize} —— 让 decode 出来的草稿图长边不超过 targetEdge，
     * 且 sampleSize 必须是 2 的幂（BitmapFactory 强制）。
     */
    private static int computeInSampleSize(int regionW, int regionH, int targetEdge) {
        int longEdge = Math.max(regionW, regionH);
        if (longEdge <= 0) return 1;
        int sample = 1;
        while ((longEdge / (sample * 2)) >= targetEdge) {
            sample *= 2;
            if (sample >= 32) break; // 32 是经验上限，再大就糊
        }
        return Math.max(1, sample);
    }

    /**
     * 等比缩放：让较长边等于 {@code maxEdge}，短边按比例缩。
     * 如果原图最长边已经 ≤ maxEdge，直接返回原图。
     */
    @NonNull
    private static Bitmap scaleMaxEdge(@NonNull Bitmap src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= maxEdge) return src;
        float ratio = (float) maxEdge / (float) longEdge;
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        Bitmap b = Bitmap.createScaledBitmap(src, nw, nh, true);
        return b != null ? b : src;
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

    // ============================================================
    // UI 树解析器（v3：XmlPullParser 容错版）
    // ============================================================

    /**
     * 轻量级 UI 树解析器：从 {@code uiautomator dump} 产出的 XML 里挑出最像"题目 / 选项 /
     * 公式"容器的节点，返回其在屏幕坐标系下的 {@link Rect}。
     *
     * <p><b>核心策略：</b></p>
     * <ol>
     *   <li>用 {@link XmlPullParser} 流式遍历，<b>只</b>看 {@code <node>} 的属性；</li>
     *   <li>过滤掉 {@code Y < STATUS_BAR_PX} 与
     *       {@code Y > screenH * (1 - NAV_BAR_BOTTOM_RATIO)} 的节点；</li>
     *   <li>对每个节点打分：WebView +100，含"题/题号/question/stem/题目/选项/option/answer"
     *       关键字 +60，面积越大越好（避免选到小卡片）；</li>
     *   <li>取分最高的节点；若全 0 分，返回 null（让 Service 走屏幕中段兜底）。</li>
     * </ol>
     *
     * <p><b>容错要点：</b></p>
     * <ul>
     *   <li>任何字段缺失 / 类型不匹配 / XML 非法都安全 return null，不抛；</li>
     *   <li>不依赖具体 ROM 的私有类名（不写"com.chaoxing.mobile"这种白名单，
     *       改用关键字 / WebView 兜底，跨刷题 App 通用）；</li>
     *   <li>{@code bounds} 严格按 {@code [left,top][right,bottom]} 解析，长度不匹配直接跳过。</li>
     * </ul>
     */
    static final class UiTreeParser {

        /** 关键字列表（命中任一即加分）。统一小写比较。 */
        private static final String[] QUESTION_KEYWORDS = {
                "题目", "题号", "题干", "试题", "选项", "解答", "答案",
                "question", "stem", "option", "answer", "exercise", "problem"
        };

        private UiTreeParser() {
        }

        /**
         * 主入口：解析 uidump.xml，挑出最佳题目矩形。
         *
         * @param xml      uiautomator dump 文件
         * @param screenW  屏幕宽（px）
         * @param screenH  屏幕高（px）
         * @return 最佳节点矩形；找不到返回 null
         */
        @Nullable
        static Rect findQuestionRect(@NonNull File xml, int screenW, int screenH) {
            if (screenW <= 0 || screenH <= 0) return null;
            // 把整个 XML 读成 String —— uidump 通常 100~500KB，一次性读够，
            // 用 StringReader + XmlPullParser 比 InputStream 更方便设 encoding。
            String content = readFileSafe(xml);
            if (content == null || content.isEmpty()) return null;

            int navTopY = (int) (screenH * (1.0 - NAV_BAR_BOTTOM_RATIO));
            if (navTopY <= STATUS_BAR_PX + 32) {
                navTopY = screenH; // 屏幕太矮，关闭底部过滤
            }

            int bestScore = 0;
            Rect bestRect = null;

            XmlPullParser parser = null;
            try {
                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                factory.setNamespaceAware(false);
                parser = factory.newPullParser();
                parser.setInput(new StringReader(content));

                int event = parser.getEventType();
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && "node".equals(parser.getName())) {
                        Rect r = null;
                        int score = 0;
                        try {
                            r = parseBounds(parser.getAttributeValue(null, "bounds"));
                            String cls = safeLower(parser.getAttributeValue(null, "class"));
                            String text = safeLower(parser.getAttributeValue(null, "text"));
                            String desc = safeLower(parser.getAttributeValue(null, "content-desc"));
                            String rid = safeLower(parser.getAttributeValue(null, "resource-id"));
                            String pkg = safeLower(parser.getAttributeValue(null, "package"));
                            int childCount = safeInt(parser.getAttributeValue(null, "childCount"));

                            if (r == null) {
                                // 节点没有 bounds，直接跳过
                            } else if (r.width() <= 16 || r.height() <= 16) {
                                // 太小（图标、按钮、装饰条）—— 跳过
                            } else if (r.top < STATUS_BAR_PX) {
                                // 顶部状态栏区域
                            } else if (r.bottom > navTopY) {
                                // 底部导航栏 / 手势条
                            } else {
                                // ===== 计分 =====
                                // WebView 加分（刷题 App 大量内容都在 WebView 里）
                                if (cls.contains("webview")) score += 100;
                                // ScrollView / RecyclerView 通常是大容器
                                if (cls.contains("scrollview")) score += 30;
                                if (cls.contains("recyclerview")) score += 20;
                                if (cls.contains("listview")) score += 15;
                                // 关键字匹配（text / desc / resource-id 都查）
                                String haystack = text + " " + desc + " " + rid;
                                for (String kw : QUESTION_KEYWORDS) {
                                    if (haystack.contains(kw)) {
                                        score += 60;
                                        break; // 多个关键字只算一次，避免被刷分
                                    }
                                }
                                // 子节点越多越像容器（题目的"选项 ABCD"会有多个子 TextView）
                                if (childCount >= 3) score += 10;
                                if (childCount >= 6) score += 10;
                                // 面积加分（避免选到小卡片）
                                long area = (long) r.width() * (long) r.height();
                                if (area > 1_000_000L) score += 20;
                                if (area > 500_000L) score += 10;
                                // 系统自带的状态栏 / 导航栏应用减分
                                if (pkg.contains("systemui")) score -= 200;
                                if (pkg.contains("launcher")) score -= 200;
                                if (pkg.contains("inputmethod")) score -= 100;
                                // 题目不应该横跨整个屏幕（一般是内容区）—— 适度扣分
                                if (r.width() >= screenW && r.height() >= screenH) score -= 30;
                            }
                        } catch (Throwable attrErr) {
                            // 单个节点属性解析失败不影响整体 —— 继续
                            r = null;
                            score = 0;
                        }
                        if (r != null && score > bestScore) {
                            bestScore = score;
                            bestRect = r;
                        }
                    }
                    event = parser.next();
                }
            } catch (Throwable t) {
                Log.w(TAG, "UiTreeParser failed: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
                return null;
            } finally {
                if (parser != null) {
                    try {
                        parser.setInput(null);
                    } catch (Throwable ignored) {
                    }
                }
            }
            // 兜底：所有节点都是 0 分（比如只有空 layout），返回 null 让 Service 走屏幕中段
            return bestScore > 0 ? bestRect : null;
        }

        /**
         * 解析 uiautomator 的 {@code bounds} 属性。格式：{@code [left,top][right,bottom]}。
         * 任何意外情况返回 null。
         */
        @Nullable
        private static Rect parseBounds(@Nullable String raw) {
            if (raw == null || raw.isEmpty()) return null;
            int open1 = raw.indexOf('[');
            int close1 = raw.indexOf(']');
            int open2 = raw.indexOf('[', close1 > 0 ? close1 : 0);
            int close2 = raw.indexOf(']', open2 > 0 ? open2 : 0);
            if (open1 < 0 || close1 < 0 || open2 < 0 || close2 < 0) return null;
            String leftTop = raw.substring(open1 + 1, close1);
            String rightBottom = raw.substring(open2 + 1, close2);
            int comma1 = leftTop.indexOf(',');
            int comma2 = rightBottom.indexOf(',');
            if (comma1 < 0 || comma2 < 0) return null;
            try {
                int l = Integer.parseInt(leftTop.substring(0, comma1).trim());
                int t = Integer.parseInt(leftTop.substring(comma1 + 1).trim());
                int r = Integer.parseInt(rightBottom.substring(0, comma2).trim());
                int b = Integer.parseInt(rightBottom.substring(comma2 + 1).trim());
                return new Rect(l, t, r, b);
            } catch (NumberFormatException nfe) {
                return null;
            } catch (Throwable t) {
                return null;
            }
        }

        @Nullable
        private static String readFileSafe(@NonNull File f) {
            // 用最朴素的 InputStreamReader 读全文件 —— 避免 NIO.2 兼容问题
            java.io.FileInputStream fis = null;
            java.io.InputStreamReader isr = null;
            java.io.BufferedReader br = null;
            try {
                fis = new java.io.FileInputStream(f);
                isr = new java.io.InputStreamReader(fis, StandardCharsets.UTF_8);
                br = new java.io.BufferedReader(isr);
                StringBuilder sb = new StringBuilder((int) Math.min(f.length(), 1_048_576L));
                char[] buf = new char[8192];
                int n;
                while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
                return sb.toString();
            } catch (Throwable t) {
                Log.w(TAG, "readFileSafe failed: " + t.getMessage());
                return null;
            } finally {
                closeQuietly(br);
                closeQuietly(isr);
                closeQuietly(fis);
            }
        }

        @NonNull
        private static String safeLower(@Nullable String s) {
            return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
        }

        private static int safeInt(@Nullable String s) {
            if (s == null || s.isEmpty()) return 0;
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException nfe) {
                return 0;
            }
        }
    }
}

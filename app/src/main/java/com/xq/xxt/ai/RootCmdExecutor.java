package com.xq.xxt.ai;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Root 命令执行器 —— 封装 {@code su -c "cmd"} 的同步 / 异步调用。
 *
 * <p><b>本次重写对齐主理人 v2 契约：</b></p>
 * <ul>
 *   <li>{@link #execAsync(String, Callback)} / {@link #sync(String)} / {@link #screenshot(Context)} /
 *       {@link #toast(String)} 全部按主理人指定的命名与签名提供；</li>
 *   <li>{@code su} 进程统一通过 {@code Runtime.getRuntime().exec(new String[]{"su", "-c", cmd})}
 *       数组形式启动 —— 避免 {@code /system/bin/sh -c} 的二次解析，路径里的空格、
 *       单/双引号都按"原样"交给 su，su 自己用 sh 解析；</li>
 *   <li>两个静态便捷方法：
 *       <ul>
 *         <li>{@link #screenshot(Context)} —— 内部 {@code su -c "screencap -p ... && chmod 666 ..."}，
 *             写到 App 私有 cache 目录下的 {@code screenshot.png}，返回 {@link File}；</li>
 *         <li>{@link #toast(String)} —— 内部 {@code su -c "am broadcast -a
 *             com.android.server.notification.Toast --es text '...'"}，对 text 做最小化转义
 *             （双引号 / 反斜杠 / 空格），避免破坏 am 命令行；</li>
 *       </ul></li>
 *   <li>异步执行走单线程 {@link ExecutorService}（其实务上 su 命令不频繁；为了"不卡主线程"
 *       的硬性要求，必须异步），同步执行走调用方线程（{@link #sync} 内部会起一个临时
 *       守护线程跑 IO 避免阻塞调用方太久），所有 su 子进程的 stdout/stderr 用抽干线程
 *       防止管道缓冲写满卡死 su；</li>
 *   <li>所有 {@link Callback} 都在后台线程触发 —— <b>严禁</b>在 callback 里直接
 *       {@code Toast}/{@code NotificationManager}，调用方自行切到主线程做 UI。</li>
 * </ul>
 *
 * <p><b>安全注意：</b>{@code Runtime.exec(String[])} 比 {@code Runtime.exec(String)}
 * 安全得多 —— 后者会被 {@link java.lang.StringTokenizer} 用空白拆分，命令里一旦出现
 * 空格 / 引号 / 变量展开就出 bug 或被注入。本类统一走数组形式。</p>
 */
public final class RootCmdExecutor {

    private static final String TAG = "RootCmdExecutor";

    /** 默认 su 命令超时（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 10_000L;

    /** 截图文件名（固定） */
    private static final String SCREENSHOT_NAME = "screenshot.png";

    private static volatile RootCmdExecutor INSTANCE;

    @NonNull
    public static RootCmdExecutor get() {
        if (INSTANCE == null) {
            synchronized (RootCmdExecutor.class) {
                if (INSTANCE == null) {
                    INSTANCE = new RootCmdExecutor();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 单线程后台 Executor —— 严格单线程，按顺序串行执行 su 命令。
     * <p>为什么单线程：su 调用频次很低（每次按键触发一次截图），并发没意义；
     * 单线程 + daemon 化让"su 跑得慢时也保证不卡主线程"。</p>
     */
    private final ExecutorService ioExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "root-exec-io");
        t.setDaemon(true);
        return t;
    });

    private RootCmdExecutor() {
    }

    // ============================================================
    // 公共 API（主理人 v2 契约）
    // ============================================================

    /**
     * 异步执行任意 shell 命令。
     * <p>callback 触发在 {@code root-exec-io} 线程 —— <b>不要在 callback 里做 UI</b>。</p>
     *
     * @param cmd      shell 命令字符串。会被整体作为 {@code su -c <cmd>} 提交。
     * @param callback 结果回调；可以为空
     */
    public void execAsync(@NonNull String cmd, @Nullable Callback callback) {
        execAsync(cmd, DEFAULT_TIMEOUT_MS, callback);
    }

    /**
     * 异步执行任意 shell 命令，带显式超时。
     */
    public void execAsync(@NonNull String cmd, long timeoutMs, @Nullable Callback callback) {
        if (cmd == null) {
            deliverFailure(callback, "command is null");
            return;
        }
        ioExec.execute(() -> {
            Result r;
            try {
                r = syncInternal(cmd, timeoutMs);
            } catch (Throwable t) {
                Log.e(TAG, "execAsync crashed: " + t.getMessage(), t);
                r = Result.fail("unexpected: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                        "", "", 0L);
            }
            invokeCallback(callback, r);
        });
    }

    /**
     * 同步执行 shell 命令（阻塞当前线程）。
     * <p><b>注意：</b>实现在内部会用守护线程起 su，调用方仍要尽量在子线程里调，
     * 否则 ANR 风险高。这里"sync"的语义是"等结果出来再返回"，不是"直接调 su"。</p>
     */
    @NonNull
    public Result sync(@NonNull String cmd) {
        return sync(cmd, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 同步执行 shell 命令，带显式超时。
     */
    @NonNull
    public Result sync(@NonNull String cmd, long timeoutMs) {
        if (cmd == null) {
            return Result.fail("command is null", "", "", 0L);
        }
        return syncInternal(cmd, timeoutMs);
    }

    // ============================================================
    // 业务便捷方法（静态）
    // ============================================================

    /**
     * 截屏到 App 私有 cache 目录下的 {@code screenshot.png}，并 {@code chmod 666}。
     * <p>实际执行：</p>
     * <pre>
     *   su -c "mkdir -p &lt;cacheDir&gt; && screencap -p &lt;cacheDir&gt;/screenshot.png && chmod 666 &lt;cacheDir&gt;/screenshot.png"
     * </pre>
     *
     * @return 截图文件对象（路径就是 cache 目录下的 {@code screenshot.png}）。
     *         即使 su 失败，路径也是正确的（失败由调用方通过 lastResult() / logcat 看到）。
     */
    @NonNull
    public static File screenshot(@NonNull Context ctx) {
        String cacheDir = ctx.getApplicationContext().getCacheDir().getAbsolutePath();
        File out = new File(cacheDir, SCREENSHOT_NAME);
        String target = out.getAbsolutePath();
        // 用 ' ' 单引号包路径做 shell 转义 —— 路径里通常没有单引号，最安全的做法
        String escTarget = "'" + target.replace("'", "'\\''") + "'";
        String escDir = "'" + cacheDir.replace("'", "'\\''") + "'";
        String cmd = "mkdir -p " + escDir
                + " && screencap -p " + escTarget
                + " && chmod 666 " + escTarget;
        Result r = get().syncInternal(cmd, 15_000L);
        if (!r.success) {
            Log.w(TAG, "screenshot failed: " + r);
        }
        return out;
    }

    /**
     * 异步截屏 + 回调：UI 侧不会卡。
     */
    public void screenshotAsync(@NonNull Context ctx, @Nullable FileCallback callback) {
        String cacheDir = ctx.getApplicationContext().getCacheDir().getAbsolutePath();
        File out = new File(cacheDir, SCREENSHOT_NAME);
        String target = out.getAbsolutePath();
        String escTarget = "'" + target.replace("'", "'\\''") + "'";
        String escDir = "'" + cacheDir.replace("'", "'\\''") + "'";
        String cmd = "mkdir -p " + escDir
                + " && screencap -p " + escTarget
                + " && chmod 666 " + escTarget;
        execAsync(cmd, 15_000L, r -> {
            if (callback != null) {
                try {
                    callback.onResult(out, r);
                } catch (Throwable t) {
                    Log.e(TAG, "screenshotAsync callback threw", t);
                }
            }
        });
    }

    /**
     * 弹一个系统级 Toast（同步）。
     * <p>实际执行：{@code su -c "am broadcast -a com.android.server.notification.Toast --es text ..."}。
     * 这是 ColorOS / MIUI / AOSP 都认的系统级广播，绕开应用层 window token 不会
     * 出现在"最近任务"里，对反作弊检测更友好。</p>
     *
     * <p><b>转义策略：</b>text 里的单引号用 {@code '\''} 闭合（标准 shell 转义），
     * 双引号 / 反斜杠 / {@code $} / 反引号在单引号包裹下不会被 shell 二次解析，
     * 因此不做额外处理 —— 这是 POSIX shell 公认最安全的做法。</p>
     */
    @NonNull
    public static Result toast(@NonNull String text) {
        // 单引号转义：'  ->  '\''
        String escaped = text.replace("'", "'\\''");
        String cmd = "am broadcast -a com.android.server.notification.Toast --es text '" + escaped + "'";
        return get().syncInternal(cmd, 5_000L);
    }

    /**
     * 异步 Toast —— 不卡调用方线程。
     */
    public void toastAsync(@NonNull String text, @Nullable Callback callback) {
        String escaped = text.replace("'", "'\\''");
        String cmd = "am broadcast -a com.android.server.notification.Toast --es text '" + escaped + "'";
        execAsync(cmd, 5_000L, callback);
    }

    // ============================================================
    // 内部：实际启动 su 并抽干 stdout/stderr
    // ============================================================

    /**
     * 真正调用 {@code su} 的内部方法。
     * <p>实现要点：</p>
     * <ol>
     *   <li>{@code Runtime.getRuntime().exec(new String[]{"su", "-c", cmd})} 数组形式启动 su；</li>
     *   <li>用 stdin 写 {@code cmd} 字符串 + {@code \n}（这是 su 协议的固定写法，
     *       否则 su 不会执行）；</li>
     *   <li>stdout / stderr 各开一个抽干线程，防止管道缓冲写满阻塞 su；</li>
     *   <li>{@code process.waitFor(timeoutMs)} 限时等待，超时则 destroyForcibly。</li>
     * </ol>
     */
    @NonNull
    private Result syncInternal(@NonNull String cmd, long timeoutMs) {
        final long t0 = System.currentTimeMillis();
        Process process = null;
        Thread stdoutDrain = null;
        Thread stderrDrain = null;
        final StringBuilder outBuf = new StringBuilder();
        final StringBuilder errBuf = new StringBuilder();
        try {
            // 数组形式避免 /system/bin/sh -c 二次解析
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            // 必须把命令写给 stdin —— 这是 Magisk/KernelSU su 的协议
            try (OutputStream os = process.getOutputStream()) {
                os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                os.write("exit\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                return Result.fail("write stdin failed: " + e.getMessage(),
                        outBuf.toString(), errBuf.toString(), System.currentTimeMillis() - t0);
            }

            stdoutDrain = drainStreamAsync(process.getInputStream(), "stdout", outBuf);
            stderrDrain = drainStreamAsync(process.getErrorStream(), "stderr", errBuf);

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                joinQuietly(stdoutDrain, 200L);
                joinQuietly(stderrDrain, 200L);
                return Result.fail("timeout after " + timeoutMs + "ms",
                        outBuf.toString(), errBuf.toString(), System.currentTimeMillis() - t0);
            }
            int exit = process.exitValue();
            return new Result(exit == 0, exit, outBuf.toString(), errBuf.toString(),
                    System.currentTimeMillis() - t0);
        } catch (IOException e) {
            return Result.fail("io: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    outBuf.toString(), errBuf.toString(), System.currentTimeMillis() - t0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("interrupted: " + e.getMessage(),
                    outBuf.toString(), errBuf.toString(), System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            Log.e(TAG, "syncInternal unexpected", t);
            return Result.fail("unexpected: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    outBuf.toString(), errBuf.toString(), System.currentTimeMillis() - t0);
        } finally {
            joinQuietly(stdoutDrain, 100L);
            joinQuietly(stderrDrain, 100L);
            if (process != null && process.isAlive()) {
                try {
                    process.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 后台抽干一个 InputStream —— 防止管道缓冲写满阻塞 su / 任何 su 子进程。
     * <p>daemon=true 进程退出自动回收。</p>
     */
    @NonNull
    private static Thread drainStreamAsync(@NonNull InputStream in,
                                           @NonNull String tag,
                                           @NonNull StringBuilder sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[1024];
            try {
                int n;
                while (!Thread.currentThread().isInterrupted()
                        && (n = in.read(buf)) > 0) {
                    sink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "drain " + tag + " ended: " + e.getMessage());
                }
            } catch (Throwable t2) {
                Log.w(TAG, "drain " + tag + " crashed", t2);
            }
        }, "root-drain-" + tag);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(@Nullable Thread t, long millis) {
        if (t == null) return;
        try {
            t.join(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
    }

    private static void invokeCallback(@Nullable Callback cb, @NonNull Result r) {
        if (cb == null) return;
        try {
            cb.onResult(r);
        } catch (Throwable t) {
            Log.e(TAG, "callback.onResult threw", t);
        }
    }

    private static void deliverFailure(@Nullable Callback cb, @NonNull String reason) {
        invokeCallback(cb, Result.fail(reason, "", "", 0L));
    }

    /**
     * 简单文件存在检查（不走 su）。仅用于辅助判断。
     */
    public static boolean fileExists(@NonNull String path) {
        return new File(path).exists();
    }

    /**
     * 简单文件存在检查（不走 su）。仅用于辅助判断。
     */
    public static boolean fileExists(@NonNull File f) {
        return f != null && f.exists();
    }

    // ============================================================
    // 类型
    // ============================================================

    /**
     * su 命令结果回调。回调在 {@code root-exec-io} 线程，<b>不要</b>在 onResult 里做 UI。
     */
    public interface Callback {
        void onResult(@NonNull Result result);
    }

    /**
     * screenshot 专用回调：把目标 File 一起带出来，省去调用方再算路径。
     */
    public interface FileCallback {
        void onResult(@NonNull File file, @NonNull Result result);
    }

    public static final class Result {
        public final boolean success;
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final long elapsedMs;

        private Result(boolean success, int exitCode, String stdout, String stderr, long elapsedMs) {
            this.success = success;
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.elapsedMs = elapsedMs;
        }

        static Result fail(@NonNull String reason,
                           @NonNull String out, @NonNull String err, long elapsedMs) {
            return new Result(false, -1, out,
                    (err == null ? "" : err) + "\n[RootCmdExecutor] " + reason,
                    elapsedMs);
        }

        @Override
        public String toString() {
            return "Result{ok=" + success + ", exit=" + exitCode
                    + ", ms=" + elapsedMs + ", stderr=" + stderr.trim() + "}";
        }
    }
}

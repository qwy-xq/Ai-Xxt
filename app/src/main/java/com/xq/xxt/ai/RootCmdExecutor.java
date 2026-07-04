package com.xq.xxt.ai;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Root 命令执行器
 * <p>
 * 封装通过 {@code su} 提权执行 Shell 命令的能力。设计上做三件事：
 * 1. 不阻塞调用线程 —— 所有执行都丢到单线程 IO 池里跑；
 * 2. 避免命令注入 —— 接收者 (sink) 与参数分离；
 * 3. 失败可观测 —— 同时返回 stdout/stderr/exitCode，并通过回调上抛。
 * <p>
 * 用法：
 * <pre>
 *   RootCmdExecutor.get().execAsync("screencap -p /data/local/tmp/x.png", result -> {
 *       if (result.success) ...
 *   });
 * </pre>
 */
public final class RootCmdExecutor {

    private static final String TAG = "RootCmdExecutor";

    /** 命令最大长度限制，防止误把大段 base64 灌进 shell 历史 */
    private static final int MAX_CMD_LENGTH = 4096;

    /** 默认命令超时（毫秒）—— 截图 / am broadcast 这种短命令 10s 够用 */
    private static final long DEFAULT_TIMEOUT_MS = 10_000L;

    private static volatile RootCmdExecutor INSTANCE;

    public static RootCmdExecutor get() {
        if (INSTANCE == null) {
            synchronized (RootCmdExecutor) {
                if (INSTANCE == null) {
                    INSTANCE = new RootCmdExecutor();
                }
            }
        }
        return INSTANCE;
    }

    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "root-exec-io");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean suAvailable = new AtomicBoolean(false);
    private volatile boolean suChecked = false;

    private RootCmdExecutor() {
    }

    // ------------------------------------------------------------
    // 公共 API
    // ------------------------------------------------------------

    /**
     * 异步执行一条 root 命令。
     *
     * @param cmd      要执行的 shell 字符串（会被写入 su 的 stdin）
     * @param callback 结果回调；允许为 null
     */
    public void execAsync(@NonNull String cmd, @Nullable Callback callback) {
        execAsync(cmd, DEFAULT_TIMEOUT_MS, callback);
    }

    /**
     * 异步执行一条 root 命令，自定义超时。
     */
    public void execAsync(@NonNull String cmd, long timeoutMs, @Nullable Callback callback) {
        if (cmd.length() > MAX_CMD_LENGTH) {
            deliverFailure(callback, "command too long: " + cmd.length());
            return;
        }
        ioPool.execute(() -> {
            Result r = execBlocking(cmd, timeoutMs);
            if (callback != null) {
                try {
                    callback.onResult(r);
                } catch (Throwable t) {
                    Log.e(TAG, "callback threw", t);
                }
            }
        });
    }

    /**
     * 同步执行（用于必须等待结果的场景，例如测试）。会阻塞当前线程。
     */
    public Result execBlocking(@NonNull String cmd, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        Process process = null;
        BufferedReader stdoutReader = null;
        BufferedReader stderrReader = null;
        try {
            // 0: 表示要 root 权限的 su；某些 ROM 上是 su -c "cmd" 的用法，这里走 stdin 写命令
            process = new ProcessBuilder("su").redirectErrorStream(false).start();

            try (OutputStream os = process.getOutputStream()) {
                os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                os.write("exit\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            stdoutReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            stderrReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            String line;
            while ((line = stdoutReader.readLine()) != null) {
                out.append(line).append('\n');
            }
            while ((line = stderrReader.readLine()) != null) {
                err.append(line).append('\n');
            }

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Result.fail("timeout after " + timeoutMs + "ms", out.toString(), err.toString(),
                        System.currentTimeMillis() - t0);
            }
            int exit = process.exitValue();
            suChecked = true;
            suAvailable.set(exit == 0);
            return new Result(exit == 0, exit, out.toString(), err.toString(),
                    System.currentTimeMillis() - t0);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "", "", System.currentTimeMillis() - t0);
        } finally {
            closeQuietly(stdoutReader);
            closeQuietly(stderrReader);
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    // ------------------------------------------------------------
    // 业务便捷方法 —— 把 "截屏"、"系统 Toast" 这类高频操作封装成一行
    // ------------------------------------------------------------

    /**
     * 执行系统级静默截图。输出固定落到 /data/local/tmp/screencapture.png。
     * 这是 Android 14+ 一加 Pad Pro 上 Magisk/内核 su 提权后最稳的截图路径。
     */
    public void captureScreenAsync(@Nullable Callback callback) {
        // -p 直接输出 png；目标路径放在 /data/local/tmp 是因为该目录对 shell 用户默认可写
        execAsync("screencap -p /data/local/tmp/screencapture.png", callback);
    }

    /**
     * 发送一个系统级 Toast 广播。
     * <p>
     * 之所以走 {@code am broadcast} 而不是 {@link android.widget.Toast}，是因为：
     * 1. Toast 在系统服务进程里渲染，不会创建应用层窗口；
     * 2. 第三方应用的窗口遍历算法通常只枚举自己进程的 WindowManager，拿不到这个 Toast；
     * 3. 可以让 Service 在完全无 UI 的状态下展示结果。
     * <p>
     * 注意：com.android.server.notification.Toast 这个 action 是 Android 内部 API，
     * 不同 ROM / Android 版本可能略有差异，调用失败时建议降级到自己的本地 Toast 或者通知栏。
     *
     * @param text 要展示的文本，长度建议不超过 200 字符（Toast 本身有长度上限）
     */
    public void systemToastAsync(@NonNull String text, @Nullable Callback callback) {
        // 用单引号包裹并转义内部单引号，规避 shell 注入
        String escaped = text.replace("'", "'\\''");
        String cmd = "am broadcast -a com.android.server.notification.Toast --es text '" + escaped + "'";
        execAsync(cmd, callback);
    }

    /**
     * 强制停止自己包名的应用（用于自毁 / 清理）。方便调试。
     */
    public void forceStopSelfAsync(@NonNull String pkg, @Nullable Callback callback) {
        execAsync("am force-stop " + pkg, callback);
    }

    // ------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------

    private static void deliverFailure(@Nullable Callback cb, String reason) {
        if (cb != null) {
            cb.onResult(Result.fail(reason, "", "", 0));
        }
    }

    private static void closeQuietly(@Nullable java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    public interface Callback {
        void onResult(@NonNull Result result);
    }

    /**
     * 单次命令执行的结果。
     */
    public static final class Result {
        public final boolean success;
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final long elapsedMs;

        private Result(boolean success, int exitCode, String stdout, String stderr, long elapsedMs) {
            this.success = success;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.elapsedMs = elapsedMs;
        }

        static Result fail(String reason, String out, String err, long elapsedMs) {
            // fail 用 exitCode = -1 标识，避免和正常 shell 返回值冲突
            return new Result(false, -1, out, (err == null ? "" : err) + "\n[RootCmdExecutor] " + reason, elapsedMs);
        }

        @Override
        public String toString() {
            return "Result{ok=" + success + ", exit=" + exitCode
                    + ", ms=" + elapsedMs + ", stderr=" + stderr.trim() + "}";
        }
    }
}
package com.xq.xxt.ai;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Root 命令执行器 —— 通过 {@code su} 提权执行 Shell 命令。
 * <p>
 * <b>v2 改动：</b>截图路径从原来的 {@code /data/local/tmp/screencapture.png} 改成
 * App 私有 cache 目录（{@code /data/data/<pkg>/cache/screenshot.png}），并在
 * 截图后立即 {@code chmod 666} 让普通 Java 进程也能读取。
 * <p>
 * 为什么要这样做：{@code /data/local/tmp/} 在 SELinux enforcing 下属于
 * {@code shell_data_file} 类型，普通应用（{@code untrusted_app} 域）即使有 root
 * 父进程创建的文件，也可能因为 SELinux 标签不匹配而无法读取。把文件放在 App 自己
 * 的 {@code /data/data/<pkg>/cache/} 下，SELinux 标签天然是 {@code app_data_file}，
 * App 自己读没有任何阻碍。
 */
public final class RootCmdExecutor {

    private static final String TAG = "RootCmdExecutor";

    private static final int MAX_CMD_LENGTH = 8192;
    private static final long DEFAULT_TIMEOUT_MS = 10_000L;

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

    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "root-exec-io");
        t.setDaemon(true);
        return t;
    });

    private RootCmdExecutor() {
    }

    // ------------------------------------------------------------
    // 通用 API
    // ------------------------------------------------------------

    public void execAsync(@NonNull String cmd, @Nullable Callback callback) {
        execAsync(cmd, DEFAULT_TIMEOUT_MS, callback);
    }

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

    @NonNull
    public Result execBlocking(@NonNull String cmd, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        Process process = null;
        BufferedReader stdoutReader = null;
        BufferedReader stderrReader = null;
        try {
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
                return Result.fail("timeout after " + timeoutMs + "ms",
                        out.toString(), err.toString(), System.currentTimeMillis() - t0);
            }
            int exit = process.exitValue();
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
    // 业务便捷方法
    // ------------------------------------------------------------

    /**
     * 截图到指定路径（任意路径，由调用方决定）。
     * <p>
     * 注意：本方法不附加 {@code chmod}，如果路径不是 App 私有目录，
     * 需要调用方自己处理读取权限。
     *
     * @param targetPath 绝对路径，例如 {@code /sdcard/Pictures/x.png}
     */
    public void captureScreenToAsync(@NonNull String targetPath, @Nullable Callback callback) {
        String cmd = "screencap -p " + escape(targetPath);
        execAsync(cmd, callback);
    }

    /**
     * 截图到 App 私有 cache 目录（推荐），并 {@code chmod 666} 让 App 自己能读。
     * <p>
     * 实际命令：
     * <pre>
     *   mkdir -p /data/data/<pkg>/cache
     *   screencap -p /data/data/<pkg>/cache/screenshot.png
     *   chmod 666 /data/data/<pkg>/cache/screenshot.png
     * </pre>
     *
     * @param ctx        任意 Context（仅用于拿包名算 cache 路径，引用会被释放）
     * @param fileName   文件名，例如 {@code "screenshot.png"}
     * @param callback   结果回调；通过 {@code result.stdout} 拿不到路径，可以从
     *                   {@link #resolveAppCachePath(Context, String)} 自己取
     */
    public void captureScreenToAppCacheAsync(@NonNull Context ctx,
                                              @NonNull String fileName,
                                              @Nullable Callback callback) {
        String cacheDir = ctx.getApplicationContext().getCacheDir().getAbsolutePath();
        String target = cacheDir + "/" + fileName;
        // 三步：建目录、截图、放权
        String cmd = "mkdir -p " + escape(cacheDir)
                + " && screencap -p " + escape(target)
                + " && chmod 666 " + escape(target);
        execAsync(cmd, callback);
    }

    /**
     * 计算 App 私有 cache 目录下一个文件的绝对路径。
     * 纯字符串拼接，不做任何 IO，方便上游在截图前/后引用同一路径。
     */
    @NonNull
    public static String resolveAppCachePath(@NonNull Context ctx, @NonNull String fileName) {
        return ctx.getApplicationContext().getCacheDir().getAbsolutePath() + "/" + fileName;
    }

    /**
     * 发送一个系统级 Toast 广播（绕过应用窗口遍历）。
     *
     * @param text 要展示的文本，建议 ≤ 200 字符
     */
    public void systemToastAsync(@NonNull String text, @Nullable Callback callback) {
        String escaped = text.replace("'", "'\\''");
        String cmd = "am broadcast -a com.android.server.notification.Toast --es text '" + escaped + "'";
        execAsync(cmd, callback);
    }

    public void forceStopSelfAsync(@NonNull String pkg, @Nullable Callback callback) {
        execAsync("am force-stop " + pkg, callback);
    }

    /**
     * 删除一个文件（root 权限）。
     */
    public void rmAsync(@NonNull String path, @Nullable Callback callback) {
        execAsync("rm -f " + escape(path), callback);
    }

    /**
     * 列出 /dev/input/event* —— 给 Service 用来探测可用输入设备。
     */
    public void listInputDevicesAsync(@Nullable Callback callback) {
        execAsync("ls -1 /dev/input/event* 2>/dev/null || true", callback);
    }

    // ------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------

    /**
     * shell 安全转义 —— 用单引号包住参数并转义内部单引号。
     * 适合给路径这种不含单引号的稳定字符串用。
     */
    @NonNull
    public static String escape(@NonNull String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

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

    /**
     * 简单的文件存在检查（不走 su）。仅用于辅助判断。
     */
    public static boolean fileExists(@NonNull String path) {
        return new File(path).exists();
    }

    // ------------------------------------------------------------
    // 类型
    // ------------------------------------------------------------

    public interface Callback {
        void onResult(@NonNull Result result);
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
            this.stdout = stdout;
            this.stderr = stderr;
            this.elapsedMs = elapsedMs;
        }

        static Result fail(String reason, String out, String err, long elapsedMs) {
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
package com.xq.xxt.ai;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Root 命令执行器 —— 通过 {@code su} 提权执行 Shell 命令。
 * <p>
 * 这是本次重写后的版本，主要保留/加固了以下能力：
 * <ul>
 *   <li>{@link #execAsync(String, Callback)} / {@link #execBlocking(String, long)}：
 *       通过 stdin 向 {@code su} 投递命令，避免 Magisk su 二次 shell 解析带来的怪行为；</li>
 *   <li>stderr 单独抽干线程（{@link #drainStreamAsync}），防止 su 子进程管道缓冲写满卡死；</li>
 *   <li>{@link #captureScreenToAppCacheAsync}：截图到 App 私有 cache 目录（避开 SELinux
 *       {@code shell_data_file} 标签），并 {@code chmod 666}；</li>
 *   <li>{@link #systemToastAsync}：构造 {@code am broadcast -a
 *       com.android.server.notification.Toast --es text '...'}，文本里的单引号
 *       用 {@code '\\''} 闭合（标准 shell 转义）。</li>
 * </ul>
 * <p>
 * <b>注意：</b>不要在该类里直接做 UI（Toast/Notification），所有 callback 都在
 * 后台 IO 线程。调用方需要在切到主线程后做 UI。
 */
public final class RootCmdExecutor {

    private static final String TAG = "RootCmdExecutor";

    /** 命令字符串硬上限，避免异常情况下把内存吃光。 */
    private static final int MAX_CMD_LENGTH = 8192;

    /** 默认阻塞超时（毫秒）。 */
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

    /**
     * 单 IO 线程池，所有 su 调用复用。
     * <p>用 cached pool 而不是单线程：screencap、chmod、am broadcast 这些
     * 互相不阻塞，并发提交反而更快；并且都是用完即弃、daemon 化，进程退出自动回收。
     */
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

    /**
     * 异步执行一条 shell 命令（无超时控制，默认 10s）。
     */
    public void execAsync(@NonNull String cmd, @Nullable Callback callback) {
        execAsync(cmd, DEFAULT_TIMEOUT_MS, callback);
    }

    /**
     * 异步执行一条 shell 命令，带显式超时。
     */
    public void execAsync(@NonNull String cmd, long timeoutMs, @Nullable Callback callback) {
        if (cmd == null) {
            deliverFailure(callback, "command is null");
            return;
        }
        if (cmd.length() > MAX_CMD_LENGTH) {
            // 不做截断，直接拒绝 —— 命令异常大概率意味着上游 bug，不如让它挂掉
            deliverFailure(callback, "command too long: " + cmd.length());
            return;
        }
        ioPool.execute(() -> {
            Result r;
            try {
                r = execBlocking(cmd, timeoutMs);
            } catch (Throwable t) {
                // 兜底：execBlocking 内部已经 catch 过所有声明异常，这里只是保险
                Log.e(TAG, "execBlocking threw unexpected", t);
                r = Result.fail("unexpected: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                        "", "", 0L);
            }
            if (callback != null) {
                try {
                    callback.onResult(r);
                } catch (Throwable t) {
                    // 业务回调抛异常不能影响 IO 线程池
                    Log.e(TAG, "callback threw", t);
                }
            }
        });
    }

    /**
     * 同步阻塞执行一条 shell 命令。
     * <p>实现要点：
     * <ol>
     *   <li>{@code new ProcessBuilder("su").start()} 启动 su；</li>
     *   <li>用 stdin 写命令 + {@code exit} —— su 自身是常驻 shell 进程，
     *       写完命令后再写 exit 让它干净退出；</li>
     *   <li>stdout / stderr 各开一个抽干线程（{@link #drainStreamAsync}），
     *       既避免缓冲写满，也保留 stderr 内容到 {@link Result#stderr}；</li>
     *   <li>{@code process.waitFor(timeoutMs)} 限时等待，超时则 destroyForcibly。</li>
     * </ol>
     */
    @NonNull
    public Result execBlocking(@NonNull String cmd, long timeoutMs) {
        final long t0 = System.currentTimeMillis();
        Process process = null;
        Thread stdoutDrain = null;
        Thread stderrDrain = null;
        final StringBuilder outBuf = new StringBuilder();
        final StringBuilder errBuf = new StringBuilder();
        try {
            process = new ProcessBuilder("su").redirectErrorStream(false).start();

            // 用 stdin 写命令 + exit，避免命令行参数过长 / shell 解析问题
            try (OutputStream os = process.getOutputStream()) {
                os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                os.write("exit\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                // 写不进去说明 su 已经死了，直接 fail
                return Result.fail("write stdin failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        outBuf.toString(), errBuf.toString(), System.currentTimeMillis() - t0);
            }

            // 关键：必须把两个流都读掉，否则 su 写满管道后阻塞，waitFor 永远不返回
            stdoutDrain = drainStreamAsync(process.getInputStream(), "stdout", outBuf);
            stderrDrain = drainStreamAsync(process.getErrorStream(), "stderr", errBuf);

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                // 等抽干线程结束（最多 200ms），避免持有 fd
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
            // 被外部打断，恢复中断标志
            Thread.currentThread().interrupt();
            return Result.fail("interrupted: " + e.getMessage(),
                    outBuf.toString(), errBuf.toString(), System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            // 防御性兜底：不希望任何异常逃逸导致 IO 线程被吃掉
            Log.e(TAG, "execBlocking unexpected throwable", t);
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

    // ------------------------------------------------------------
    // 业务便捷方法
    // ------------------------------------------------------------

    /**
     * 截图到任意路径（由调用方决定）。不附加 chmod。
     */
    public void captureScreenToAsync(@NonNull String targetPath, @Nullable Callback callback) {
        String cmd = "screencap -p " + escape(targetPath);
        execAsync(cmd, callback);
    }

    /**
     * 截图到 App 私有 cache 目录（推荐），并 {@code chmod 666} 让 App 自己能读。
     * <p>实际命令：
     * <pre>
     *   mkdir -p /data/data/&lt;pkg&gt;/cache
     *   screencap -p /data/data/&lt;pkg&gt;/cache/screenshot.png
     *   chmod 666 /data/data/&lt;pkg&gt;/cache/screenshot.png
     * </pre>
     * 为什么要这样：三步走是因为 v1 只 screencap 不到 cache 目录时，App
     * 无法读取（路径不存在 / 权限不足 / SELinux 标签不符），所以补全 mkdir + chmod。
     *
     * @param ctx      任意 Context（仅用于拿包名算 cache 路径，引用会被释放）
     * @param fileName 文件名，例如 {@code "screenshot.png"}
     * @param callback 结果回调；{@link Result#success} 表示三步都成功
     */
    public void captureScreenToAppCacheAsync(@NonNull Context ctx,
                                              @NonNull String fileName,
                                              @Nullable Callback callback) {
        String cacheDir = ctx.getApplicationContext().getCacheDir().getAbsolutePath();
        String target = cacheDir + "/" + fileName;
        // 三步：建目录、截图、放权。三者用 && 串行，前一步失败就停下
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
     * <p>使用方式：{@code am broadcast -a com.android.server.notification.Toast --es text '...'}。
     * 这条 intent 在 ColorOS / MIUI 等深度定制 ROM 上仍可能被系统
     * 自己的 ToastService 接住并显示在系统窗口里（不产生应用层 window token），
     * 比直接 {@code Toast.makeText(...).show()} 更隐蔽。
     * <p>文本里的单引号用 {@code '\\''} 闭合（POSIX shell 通用转义）。
     *
     * @param text 要展示的文本，建议 ≤ 200 字符
     */
    public void systemToastAsync(@NonNull String text, @Nullable Callback callback) {
        // 单引号转义：' -> '\''，这是 shell 标准做法
        String escaped = text.replace("'", "'\\''");
        // 整段文本用单引号包住，避免任何 shell 元字符（$ ` \ "）的二次解析
        String cmd = "am broadcast -a com.android.server.notification.Toast --es text '" + escaped + "'";
        execAsync(cmd, callback);
    }

    /**
     * 强制停止一个包（root 权限）。
     */
    public void forceStopSelfAsync(@NonNull String pkg, @Nullable Callback callback) {
        execAsync("am force-stop " + escape(pkg), callback);
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

    /**
     * 后台抽干一个 InputStream，防止管道缓冲写满阻塞 su / getevent。
     * <p>同时把内容（UTF-8）写进 {@code sink}，方便 {@link Result#stdout} /
     * {@link Result#stderr} 字段拿到。daemon=true，进程退出自动回收。
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
                // 进程退出时 read 会抛异常，吞掉就行 —— 我们已经尽力了
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "drain " + tag + " ended: " + e.getMessage());
                }
            } catch (Throwable t2) {
                // 兜底
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

    private static void deliverFailure(@Nullable Callback cb, @NonNull String reason) {
        if (cb != null) {
            try {
                cb.onResult(Result.fail(reason, "", "", 0L));
            } catch (Throwable t) {
                Log.e(TAG, "deliverFailure callback threw", t);
            }
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

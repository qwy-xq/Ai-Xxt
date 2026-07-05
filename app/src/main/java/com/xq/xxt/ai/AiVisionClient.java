package com.xq.xxt.ai;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI Vision 客户端 —— 把本地截图丢给 OpenAI 兼容 Chat Completions 接口。
 *
 * <p><b>本次重写对齐主理人 v2 契约：</b></p>
 * <ol>
 *   <li>顶层入口是 {@link #analyze(File, Callback)} —— 接收一个本地图片文件和
 *       一个 {@link Callback}，把 Vision 模型的最终文本答案回传；</li>
 *   <li>API key / baseUrl / model 来源优先级：
 *       <ol>
 *         <li>{@link #setConfig(String, String, String)} 注入的最新值（最高优先）；</li>
 *         <li>{@link #init(Context)} 后从 {@link ConfigStore} 读（每次重新读取，避免缓存）；</li>
 *         <li>实在没有时用占位常量（详见 {@link #PLACEHOLDER_API_KEY} / {@link #PLACEHOLDER_BASE_URL} /
 *             {@link #PLACEHOLDER_MODEL}）—— <b>占位常量仅用于"未配置时让链路不挂"</b>，
 *             实际请求会被上游 HTTP 401 拒掉，走 {@code onFailure}。</li>
 *       </ol>
 *   </li>
 *   <li>JSON 解析 try-catch 包死：任何 {@link JSONException} / {@link NullPointerException} /
 *       {@link IndexOutOfBoundsException} 都视为"无答案"，走 {@code onFailure}，<b>不让整条线程死</b>；</li>
 *   <li>Base64 <b>必须</b>用 {@link Base64#NO_WRAP} —— {@code Base64.DEFAULT} 每 76 字符塞一个 {@code \n}，
 *       拼到 JSON 字符串里会撑爆字段，触发 HTTP 400（这是 v1 死穴，已修）；</li>
 *   <li>本地 IO 用纯 JDK 的 {@link FileInputStream}，不碰 {@code java.nio.file.Files}，
 *       后者是 Android API 26+ 才有，与本项目 {@code minSdk=24} 不兼容（这是 v1 死穴，已修）。</li>
 * </ol>
 *
 * <p><b>线程模型：</b>本类的 {@link Callback#onSuccess(String)} / {@link Callback#onFailure(Throwable)}
 * 都在 {@code root-exec-io} / OkHttp dispatcher 线程 —— <b>严禁</b>在 callback 里直接
 * {@code Toast} / {@code NotificationManager}。业务侧应在 callback 内
 * {@code new android.os.Handler(android.os.Looper.getMainLooper()).post(...)} 切回主线程。</p>
 */
public final class AiVisionClient {

    private static final String TAG = "AiVisionClient";

    // ------------------------------------------------------------
    // 占位常量 —— 仅用于"没配置时让链路不挂"，请求一定会被上游 401 拒掉
    // ------------------------------------------------------------

    /**
     * 占位 API Key。注释清楚是占位：未配置时用这个值发请求，OpenAI 必然返回 401，
     * 然后 {@link Callback#onFailure} 会被调用，业务侧能拿到错误。
     */
    static final String PLACEHOLDER_API_KEY = "sk-PLACEHOLDER-CONFIGURE-IN-MAIN-ACTIVITY";

    /** 占位 Base URL —— 指向 OpenAI 官方。占位模式下请求同样会 401。 */
    static final String PLACEHOLDER_BASE_URL = "https://api.openai.com";

    /** 占位 Model。 */
    static final String PLACEHOLDER_MODEL = "gpt-4o-mini";

    private static volatile AiVisionClient INSTANCE;

    /**
     * 在 Application.onCreate（或 MainActivity / Service onCreate）里调用一次，注入 Context。
     * 之后 {@link #get()} 拿到的实例就能用 {@link ConfigStore} 读最新配置。
     */
    public static synchronized void init(@NonNull Context ctx) {
        if (INSTANCE == null) {
            synchronized (AiVisionClient.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AiVisionClient(new ConfigStore(ctx));
                }
            }
        } else {
            // 允许热替换 ConfigStore
            INSTANCE.configStore = new ConfigStore(ctx);
        }
    }

    @NonNull
    public static AiVisionClient get() {
        if (INSTANCE == null) {
            throw new IllegalStateException(
                    "AiVisionClient not initialized; call init(Context) in Application/Activity first");
        }
        return INSTANCE;
    }

    /**
     * OkHttp 单例 —— 复用连接池，避免每次请求重建。
     * <p>超时：connect 15s / read 60s / write 30s。
     * read 给 60s 是因为 Vision 模型推理 1k token 偶尔要 20~40s，
     * read 太短会在响应还没读完时断流。</p>
     */
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private volatile ConfigStore configStore;

    /** 三元组覆盖（apiKey/baseUrl/model 任意一项非 null 即覆盖 ConfigStore 里的同名字段） */
    @Nullable private volatile String overrideApiKey;
    @Nullable private volatile String overrideBaseUrl;
    @Nullable private volatile String overrideModel;

    private AiVisionClient(@NonNull ConfigStore store) {
        this.configStore = store;
    }

    // ============================================================
    // 公共 API（主理人 v2 契约）
    // ============================================================

    /**
     * 注入最新配置；任意参数为 null 表示该字段继续走 ConfigStore / 占位。
     * <p>MainActivity 保存配置后立即调一次本方法，下一次 {@link #analyze} 立刻生效。</p>
     */
    public void setConfig(@Nullable String apiKey, @Nullable String baseUrl, @Nullable String model) {
        this.overrideApiKey = apiKey;
        this.overrideBaseUrl = baseUrl;
        this.overrideModel = model;
    }

    /**
     * <b>主入口。</b>异步分析一张本地截图，把模型的最终文本答案回传给 {@code cb}。
     * <p>典型用法：</p>
     * <pre>
     *   File png = RootCmdExecutor.screenshot(ctx);
     *   AiVisionClient.get().analyze(png, new AiVisionClient.Callback() {
     *       public void onSuccess(String answer) { ... }
     *       public void onFailure(Throwable t)  { ... }
     *   });
     * </pre>
     *
     * <p>本方法会根据 <b>文件后缀</b> 自动推断 MIME：
     * {@code .jpg / .jpeg → image/jpeg}，{@code .png → image/png}，
     * 其他 / 缺后缀 → {@code image/jpeg}（v3 流水线里裁剪产物就是 JPEG 临时文件，
     * 走 jpeg 路径最稳）。data-url 始终用 {@link Base64#NO_WRAP} 编码。</p>
     *
     * <p>callback 都在后台线程触发，调用方需自行切主线程做 UI。</p>
     *
     * @param image 本地图片文件（PNG / JPEG / WebP，BitmapFactory 能解即可）。
     *              <b>不能为空、必须 exists()、可读。</b>
     * @param cb    结果回调；可以为空。任一异常路径都走 onFailure，绝不抛
     */
    public void analyze(@NonNull File image, @Nullable Callback cb) {
        if (image == null || !image.exists() || !image.isFile()) {
            invokeFailure(cb, new IOException("image file not found: " + image));
            return;
        }
        // 读盘 + Base64 编码在 background 线程做，IO 完了再走 OkHttp（OkHttp 自己在 dispatcher 线程）
        ioExec.execute(() -> {
            try {
                String apiKey = effectiveApiKey();
                String baseUrl = effectiveBaseUrl();
                String model = effectiveModel();

                if (apiKey.isEmpty()) {
                    invokeFailure(cb, new IllegalStateException(
                            "apiKey is empty; please configure it in MainActivity first"));
                    return;
                }
                if (baseUrl.isEmpty()) {
                    invokeFailure(cb, new IllegalStateException("baseUrl is empty"));
                    return;
                }
                if (model.isEmpty()) {
                    invokeFailure(cb, new IllegalStateException("model is empty"));
                    return;
                }

                // 关键：NO_WRAP 必填；MIME 按后缀探测
                String mime = detectMime(image);
                String b64 = encodeFileToBase64(image.getAbsolutePath());
                String body = buildRequestBody(b64, DEFAULT_PROMPT, model, mime);
                doRequest(baseUrl, apiKey, body, cb);
            } catch (Throwable t) {
                // 任何异常（IO / JSON / NPE）都吞下走 onFailure，不让线程死
                Log.e(TAG, "analyze failed", t);
                invokeFailure(cb, t);
            }
        });
    }

    /**
     * <b>主入口（带 MIME 提示）。</b>和 {@link #analyze(File, Callback)} 一样，
     * 只是允许调用方显式指定 MIME，避免被奇怪的文件后缀坑到。
     * <p>MIME 推荐值：{@code image/png} / {@code image/jpeg} / {@code image/webp}。
     * 传 null 或空串则回退到 {@link #detectMime(File)} 探测。</p>
     */
    public void analyze(@NonNull File image, @Nullable String mimeHint, @Nullable Callback cb) {
        if (image == null || !image.exists() || !image.isFile()) {
            invokeFailure(cb, new IOException("image file not found: " + image));
            return;
        }
        final String mime;
        if (mimeHint == null || mimeHint.isEmpty()) {
            mime = detectMime(image);
        } else {
            mime = mimeHint.toLowerCase(java.util.Locale.ROOT);
        }
        // 复用上面的逻辑：通过 analyze 自身重载
        final String finalMime = mime;
        ioExec.execute(() -> {
            try {
                String apiKey = effectiveApiKey();
                String baseUrl = effectiveBaseUrl();
                String model = effectiveModel();

                if (apiKey.isEmpty()) {
                    invokeFailure(cb, new IllegalStateException(
                            "apiKey is empty; please configure it in MainActivity first"));
                    return;
                }
                if (baseUrl.isEmpty()) {
                    invokeFailure(cb, new IllegalStateException("baseUrl is empty"));
                    return;
                }
                if (model.isEmpty()) {
                    invokeFailure(cb, new IllegalStateException("model is empty"));
                    return;
                }

                String b64 = encodeFileToBase64(image.getAbsolutePath());
                String body = buildRequestBody(b64, DEFAULT_PROMPT, model, finalMime);
                doRequest(baseUrl, apiKey, body, cb);
            } catch (Throwable t) {
                Log.e(TAG, "analyze failed", t);
                invokeFailure(cb, t);
            }
        });
    }

    /**
     * 按文件后缀探测 MIME。命中规则：
     * <ul>
     *   <li>*.jpg / *.jpeg → image/jpeg</li>
     *   <li>*.png         → image/png</li>
     *   <li>*.webp        → image/webp</li>
     *   <li>其他 / 缺后缀 → image/jpeg（v3 裁剪产物兜底）</li>
     * </ul>
     */
    @NonNull
    public static String detectMime(@NonNull File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot < 0) return "image/jpeg";
        String ext = n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        switch (ext) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "webp":
                return "image/webp";
            default:
                return "image/jpeg";
        }
    }

    /**
     * 默认提示词 —— 跟 BackgroundAssistService 里的语义一致。
     */
    public static final String DEFAULT_PROMPT =
            "你是一个屏幕内容助手。请仔细查看这张截图，"
                    + "如果里面是选择题或判断题，只输出最终答案（字母或 True/False），不要解释；"
                    + "如果是问答题，给出简洁、可直接抄写的答案；"
                    + "如果没有可回答的内容，回复 'NO_QUESTION'。";

    /**
     * 单线程 Executor —— 专门跑"读盘 + Base64 编码"准备阶段。
     * 跟 RootCmdExecutor 共享一个池意义不大，分开管理更清晰。
     */
    private final java.util.concurrent.ExecutorService ioExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "vision-prepare");
                t.setDaemon(true);
                return t;
            });

    // ============================================================
    // 配置解析（override > ConfigStore > 占位）
    // ============================================================

    @NonNull
    private String effectiveApiKey() {
        if (overrideApiKey != null && !overrideApiKey.isEmpty()) return overrideApiKey.trim();
        if (configStore != null) {
            String v = configStore.getApiKey();
            if (!v.isEmpty()) return v;
        }
        return PLACEHOLDER_API_KEY;
    }

    @NonNull
    private String effectiveBaseUrl() {
        if (overrideBaseUrl != null && !overrideBaseUrl.isEmpty()) return overrideBaseUrl.trim();
        if (configStore != null) {
            String v = configStore.getBaseUrl();
            if (!v.isEmpty()) return v;
        }
        return PLACEHOLDER_BASE_URL;
    }

    @NonNull
    private String effectiveModel() {
        if (overrideModel != null && !overrideModel.isEmpty()) return overrideModel.trim();
        if (configStore != null) {
            String v = configStore.getModel();
            if (!v.isEmpty()) return v;
        }
        return PLACEHOLDER_MODEL;
    }

    // ============================================================
    // Base64 + IO（API 24 兼容）
    // ============================================================

    /**
     * 把本地文件编码为 OpenAI image_url 所需的 data url 形式
     * （{@code data:<mime>;base64,xxxx}）。
     * <p><b>关键：</b>使用 {@link Base64#NO_WRAP}，避免 76 字符换行污染 JSON。</p>
     * <p><b>兼容性：</b>此处<b>不用</b> {@code java.nio.file.Files.readAllBytes} 之类
     * NIO.2 工具方法 —— 它们是 Android API 26 (Oreo) 才引入的，与本项目
     * {@code minSdk=24}（Android 7.0/7.1）不兼容，在低版本系统上会
     * {@code NoClassDefFoundError} 整条 Vision 链路直接挂掉。改用
     * {@link FileInputStream} + try-with-resources 手动读完字节数组，
     * 是 Android 1.0 时代就有的稳定 API，所有 API level 都能跑。</p>
     *
     * @param path 本地图片文件路径
     * @param mime data url 中使用的 MIME（如 {@code image/jpeg} / {@code image/png}）；
     *             传 null/空会回退到 {@code image/jpeg}
     */
    @NonNull
    public static String encodeFileToDataUrl(@NonNull String path, @Nullable String mime)
            throws IOException {
        byte[] bytes = readAllBytesCompat(path);
        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        String safeMime = (mime == null || mime.isEmpty()) ? "image/jpeg" : mime;
        return "data:" + safeMime + ";base64," + b64;
    }

    /**
     * 兼容旧调用方：data url 强制用 {@code image/png}。仅在"确认图片是 PNG"时使用。
     */
    @NonNull
    public static String encodeFileToDataUrl(@NonNull String path) throws IOException {
        return encodeFileToDataUrl(path, "image/png");
    }

    /**
     * 仅返回裸 base64 字符串。同样用 {@link Base64#NO_WRAP}。
     */
    @NonNull
    public static String encodeFileToBase64(@NonNull String path) throws IOException {
        byte[] bytes = readAllBytesCompat(path);
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    /**
     * 兼容 Android 7.0/7.1 (API 24/25) 的"把文件读到 byte[]"实现。
     * <p>不能调用 {@code java.nio.file.Files.readAllBytes}，那是 API 26+；
     * 这里用最朴素的 {@link FileInputStream} 循环 read，直到读满 {@code f.length()}
     * 或遇 EOF（提前 EOF 视为 IOException，由调用方走 onFailure 路径）。</p>
     * <p>try-with-resources 保证 fd 不泄漏；异常全部上抛，不在内部吞。</p>
     */
    @NonNull
    private static byte[] readAllBytesCompat(@NonNull String path) throws IOException {
        File f = new File(path);
        byte[] bytes = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int read = 0;
            while (read < bytes.length) {
                int n = fis.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    // 文件大小在两次调用之间被改小（被并发 truncate 等），
                    // 视为 IO 失败，不静默处理
                    throw new IOException("unexpected EOF at " + read + "/" + bytes.length + ": " + path);
            }
                read += n;
            }
            return bytes;
        }
    }

    // ============================================================
    // HTTP 请求 / 响应解析
    // ============================================================

    /**
     * 构造标准 OpenAI Chat Completions 请求体。
     * <p>结构：</p>
     * <pre>
     * {
     *   "model": "&lt;model&gt;",
     *   "messages": [
     *     {
     *       "role": "user",
     *       "content": [
     *         {"type": "text", "text": "&lt;prompt&gt;"},
     *         {"type": "image_url", "image_url": {"url": "data:&lt;mime&gt;;base64,..."}}
     *       ]
     *     }
     *   ],
     *   "max_tokens": 1024
     * }
     * </pre>
     *
     * @param mime MIME 字符串（如 {@code image/jpeg} / {@code image/png}），
     *             会出现在 {@code data:<mime>;base64,} 里。传入空 / null 时回退为
     *             {@code image/jpeg}（v3 裁剪产物就是 JPEG，最稳的兜底）。
     */
    @NonNull
    private String buildRequestBody(@NonNull String base64Image,
                                    @NonNull String prompt,
                                    @NonNull String model,
                                    @Nullable String mime) throws JSONException {
        String safeMime = (mime == null || mime.isEmpty()) ? "image/jpeg" : mime;

        JSONObject textPart = new JSONObject()
                .put("type", "text")
                .put("text", prompt);

        JSONObject imageUrl = new JSONObject()
                .put("url", "data:" + safeMime + ";base64," + base64Image);

        JSONObject imagePart = new JSONObject()
                .put("type", "image_url")
                .put("image_url", imageUrl);

        JSONArray content = new JSONArray()
                .put(textPart)
                .put(imagePart);

        JSONObject message = new JSONObject()
                .put("role", "user")
                .put("content", content);

        JSONArray messages = new JSONArray().put(message);

        return new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", 1024)
                .toString();
    }

    /**
     * 实际发请求。OkHttp 回调在子线程 —— 我们只透传 String / Throwable，
     * UI 由调用方自己切主线程处理。
     */
    private void doRequest(@NonNull String baseUrl,
                           @NonNull String apiKey,
                           @NonNull String body,
                           @Nullable Callback cb) {
        // 兼容 baseUrl 末尾带不带 "/"
        String url = baseUrl.endsWith("/")
                ? baseUrl + "v1/chat/completions"
                : baseUrl + "/v1/chat/completions";

        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(body, MediaType.parse("application/json; charset=utf-8")))
                .build();

        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "network failure: " + e.getMessage());
                invokeFailure(cb, e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    String respBody = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) {
                        String snippet = snippetOf(respBody);
                        String msg = "http " + r.code() + ": " + snippet;
                        Log.w(TAG, msg);
                        invokeFailure(cb, new IOException(msg));
                        return;
                    }
                    String content = extractMessageContent(respBody);
                    if (content == null || content.isEmpty()) {
                        String snippet = snippetOf(respBody);
                        Log.e(TAG, "empty content. raw(<=500)=" + snippet);
                        invokeFailure(cb, new IOException("empty content in response: " + snippet));
                        return;
                    }
                    invokeSuccess(cb, content);
                } catch (Throwable t) {
                    // 任何 JSONException / NPE / IO 都在这里兜住
                    Log.e(TAG, "parse response failed", t);
                    invokeFailure(cb, t);
                }
            }
        });
    }

    /**
     * 从 OpenAI 兼容响应里抠出 {@code choices[0].message.content} 文本。
     * <p>兼容 content 的两种形态：</p>
     * <ul>
     *   <li>String —— 直接返回；</li>
     *   <li>JSONArray —— 遍历 type=="text" 的 part，把 text 拼起来。</li>
     * </ul>
     * 任何字段缺失 / JSONException / content 为空，都返回 null（不抛）。
     * 这样调用方可以统一走 onFailure 路径。
     */
    @Nullable
    private String extractMessageContent(@NonNull String body) {
        try {
            JSONObject root = new JSONObject(body);
            if (!root.has("choices")) {
                Log.w(TAG, "no 'choices' in response");
                return null;
            }
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                Log.w(TAG, "choices is null or empty");
                return null;
            }
            JSONObject first;
            try {
                first = choices.getJSONObject(0);
            } catch (JSONException | IndexOutOfBoundsException e) {
                Log.w(TAG, "choices[0] not object: " + e.getMessage());
                return null;
            }
            // 兼容部分老式 / 类补全接口：直接在 choice 上有 text 字段
            JSONObject message = first.optJSONObject("message");
            if (message == null) {
                String directText = first.optString("text", null);
                if (directText != null && !directText.isEmpty()) {
                    return directText;
                }
                Log.w(TAG, "no 'message' and no 'text' on choice[0]");
                return null;
            }

            Object content = message.opt("content");
            if (content == null || content == JSONObject.NULL) {
                Log.w(TAG, "message.content is null");
                return null;
            }

            if (content instanceof String) {
                String s = (String) content;
                return s.isEmpty() ? null : s;
            }
            if (content instanceof JSONArray) {
                JSONArray arr = (JSONArray) content;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject part = arr.optJSONObject(i);
                    if (part == null) continue;
                    String type = part.optString("type", "");
                    if ("text".equals(type) || "output_text".equals(type)) {
                        String t = part.optString("text", "");
                        if (!t.isEmpty()) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(t);
                        }
                    }
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            Log.w(TAG, "unsupported content type: " + content.getClass().getSimpleName());
            return null;
        } catch (JSONException e) {
            Log.w(TAG, "extractMessageContent parse failed: " + e.getMessage());
            return null;
        } catch (Throwable t) {
            // NPE / IndexOutOfBounds / 等都包住 —— 决不让线程死
            Log.e(TAG, "extractMessageContent unexpected", t);
            return null;
        }
    }

    @NonNull
    private static String snippetOf(@Nullable String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    private static void invokeSuccess(@Nullable Callback cb, @NonNull String answer) {
        if (cb == null) return;
        try {
            cb.onSuccess(answer);
        } catch (Throwable t) {
            Log.e(TAG, "callback.onSuccess threw", t);
        }
    }

    private static void invokeFailure(@Nullable Callback cb, @NonNull Throwable t) {
        if (cb == null) return;
        try {
            cb.onFailure(t);
        } catch (Throwable inner) {
            Log.e(TAG, "callback.onFailure threw", inner);
        }
    }

    // ============================================================
    // 类型
    // ============================================================

    /**
     * 主理人 v2 契约的回调接口。简单 onSuccess(String) / onFailure(Throwable)。
     * <p>两个方法都在后台线程触发，调用方需自行切主线程做 UI。</p>
     */
    public interface Callback {
        void onSuccess(@NonNull String answer);

        void onFailure(@NonNull Throwable error);
    }
}

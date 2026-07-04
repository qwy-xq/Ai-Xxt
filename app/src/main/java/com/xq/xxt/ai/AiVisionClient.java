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
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI Vision 客户端 —— 把本地截图丢给 OpenAI 兼容 Chat Completions 接口。
 * <p>
 * <b>本次重写强调的健壮性点：</b>
 * <ol>
 *   <li><b>Base64 死穴</b>：{@link #encodeFileToBase64} / {@link #encodeFileToDataUrl}
 *       显式使用 {@link Base64#NO_WRAP}。{@code Base64.DEFAULT} 会每 76 个字符插一个 {@code \n}，
 *       直接塞进 JSON image_url 会破坏字符串，导致上游 HTTP 400；</li>
 *   <li><b>多线程死穴</b>：OkHttp {@code enqueue} 回调在子线程。本类只负责把
 *       {@link ResultCallback} 透传给上游，<b>不在子线程做 Toast/Notification/UI</b>。
 *       业务侧应在 {@code onSuccess} / {@code onFailure} 里自行切回主线程；</li>
 *   <li><b>JSON 健壮性</b>：构造标准 OpenAI Chat Completions body；解析
 *       {@code choices[0].message.content} 时，兼容两种中转站常见格式：
 *       <ul>
 *         <li>{@code content} 是 {@code String}（最常见）</li>
 *         <li>{@code content} 是 {@code [{type:"text", text:"..."}, ...]} 数组
 *             （部分 OpenAI 兼容网关 / 多模态输出格式）</li>
 *       </ul>
 *       解析失败 / 字段缺失 / content 空串都会走 {@code onFailure} 并
 *       {@code Log.e} 带原始响应前 500 字，绝不让整条线程死。</li>
 *   <li><b>无第三方依赖</b>：仅用 OkHttp + org.json（Android SDK 自带）。</li>
 * </ol>
 * <p>
 * 配置（apiKey / baseUrl / model）由 {@link ConfigStore} 持有，每次调用都
 * 重新读取 —— 用户在 MainActivity 改了保存，下一次请求立刻生效。
 */
public final class AiVisionClient {

    private static final String TAG = "AiVisionClient";

    private static volatile AiVisionClient INSTANCE;

    /**
     * 在 Application.onCreate（或 MainActivity onCreate）里调用一次，注入 Context。
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
            // 允许热替换 ConfigStore（比如测试场景），一般不需要
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
     * 超时配置：connect 15s / read 60s / write 30s。
     * 为什么 read 给 60s：Vision 模型推理 1k token 偶尔要 20~40s，read 太短
     * 会在响应还没读完时断流。
     */
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private volatile ConfigStore configStore;

    private AiVisionClient(@NonNull ConfigStore store) {
        this.configStore = store;
    }

    // ------------------------------------------------------------
    // 公共 API
    // ------------------------------------------------------------

    /**
     * 异步提交一张截图给 Vision 模型。
     * <p>
     * 每次调用都会从 {@link ConfigStore} 重新读取 apiKey / baseUrl / model。
     * <p>
     * <b>线程模型：</b>准备阶段（读盘 + 编码）会启动一个 {@code vision-prepare}
     * 线程；OkHttp {@code enqueue} 之后回调也在子线程。{@code cb} 的
     * {@code onSuccess} / {@code onFailure} 都在子线程，调用方需要自行
     * {@code new Handler(Looper.getMainLooper()).post(...)} 切到主线程做 UI。
     *
     * @param imagePath 本地图片绝对路径（通常为 app 私有 cache 下的 screenshot.png）
     * @param prompt    发送给模型的文本提示
     * @param cb        结果回调；可以为空
     */
    public void sendImageAsync(@NonNull String imagePath,
                               @NonNull String prompt,
                               @Nullable ResultCallback cb) {
        new Thread(() -> {
            try {
                String apiKey = configStore.getApiKey();
                String baseUrl = configStore.getBaseUrl();
                String model = configStore.getModel();

                if (apiKey == null || apiKey.isEmpty()) {
                    deliverFailure(cb, new IllegalStateException(
                            "apiKey is empty; please configure it in MainActivity first"));
                    return;
                }
                if (baseUrl == null || baseUrl.isEmpty()) {
                    deliverFailure(cb, new IllegalStateException("baseUrl is empty"));
                    return;
                }
                if (model == null || model.isEmpty()) {
                    deliverFailure(cb, new IllegalStateException("model is empty"));
                    return;
                }

                String b64 = encodeFileToBase64(imagePath);
                String body = buildRequestBody(b64, prompt, model);
                doRequest(baseUrl, apiKey, body, cb);
            } catch (Throwable t) {
                Log.e(TAG, "sendImageAsync prepare failed", t);
                deliverFailure(cb, t);
            }
        }, "vision-prepare").start();
    }

    /**
     * 把本地文件编码为 OpenAI image_url 所需的 data url 形式
     * （{@code data:image/png;base64,xxxx}）。
     * <p><b>关键：</b>使用 {@link Base64#NO_WRAP}，避免 76 字符换行污染 JSON。
     * <p><b>兼容性：</b>此处<b>不用</b> {@code java.nio.file.Files.readAllBytes} 之类
     * NIO.2 工具方法 —— 它们是 Android API 26 (Oreo) 才引入的，与本项目
     * {@code minSdk=24}（Android 7.0/7.1）不兼容，在低版本系统上会
     * {@code NoClassDefFoundError} 整条 Vision 链路直接挂掉。改用
     * {@link FileInputStream} + try-with-resources 手动读完字节数组，
     * 是 Android 1.0 时代就有的稳定 API，所有 API level 都能跑。
     */
    @NonNull
    public static String encodeFileToDataUrl(@NonNull String path) throws IOException {
        byte[] bytes = readAllBytesCompat(path);
        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        return "data:image/png;base64," + b64;
    }

    /**
     * 仅返回裸 base64 字符串。同样用 {@link Base64#NO_WRAP}。
     * <p>同样走 {@link #readAllBytesCompat}，避免 {@code java.nio.file} 在低 API 上挂掉。
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
     * 或遇 EOF（提前 EOF 视为 IOException，由调用方走 onFailure 路径）。
     * <p>try-with-resources 保证 fd 不泄漏；异常全部上抛，不在内部吞。
     */
    @NonNull
    private static byte[] readAllBytesCompat(@NonNull String path) throws IOException {
        File f = new File(path);
        // 防御：length() == 0 时分配 0 长度数组也合法，但几乎一定是文件被截断/并发删除
        byte[] bytes = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int read = 0;
            while (read < bytes.length) {
                int n = fis.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    // 文件大小在两次调用之间被改小（被并发 chmod/chmod 后被另一进程截断等），
                    // 视为 IO 失败，不静默处理
                    throw new IOException("unexpected EOF at " + read + "/" + bytes.length + ": " + path);
                }
                read += n;
            }
            return bytes;
        }
    }

    // ------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------

    /**
     * 构造标准 OpenAI Chat Completions 请求体。
     * <p>结构：
     * <pre>
     * {
     *   "model": "&lt;model&gt;",
     *   "messages": [
     *     {
     *       "role": "user",
     *       "content": [
     *         {"type": "text", "text": "&lt;prompt&gt;"},
     *         {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
     *       ]
     *     }
     *   ],
     *   "max_tokens": 1024
     * }
     * </pre>
     * 用 org.json 而不是 Gson —— Android SDK 自带，避免引入第三方包。
     */
    @NonNull
    private String buildRequestBody(@NonNull String base64Image,
                                    @NonNull String prompt,
                                    @NonNull String model) throws JSONException {
        JSONObject textPart = new JSONObject()
                .put("type", "text")
                .put("text", prompt);

        JSONObject imageUrl = new JSONObject()
                .put("url", "data:image/png;base64," + base64Image);

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
     * 实际发请求。OkHttp 回调在子线程 —— 我们只透传 Result / Throwable，
     * UI 由调用方自己切主线程处理。
     */
    private void doRequest(@NonNull String baseUrl,
                           @NonNull String apiKey,
                           @NonNull String body,
                           @Nullable ResultCallback cb) {
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

        http.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "network failure: " + e.getMessage());
                deliverFailure(cb, e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    String respBody = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) {
                        // HTTP 非 2xx 整体当作失败。截取前 500 字 dump 出来，
                        // 方便排查（大部分中转站错误信息是 JSON，但 body 可能很大）
                        String snippet = respBody == null ? "" : respBody;
                        if (snippet.length() > 500) snippet = snippet.substring(0, 500) + "…";
                        String msg = "http " + r.code() + ": " + snippet;
                        Log.w(TAG, msg);
                        deliverFailure(cb, new IOException(msg));
                        return;
                    }
                    String content = extractMessageContent(respBody);
                    if (content == null) {
                        // 解析失败 / 字段缺失 / content 为空 —— 都视为失败
                        String snippet = respBody == null ? "" : respBody;
                        if (snippet.length() > 500) snippet = snippet.substring(0, 500) + "…";
                        Log.e(TAG, "extractMessageContent returned null. raw(<=500)=" + snippet);
                        deliverFailure(cb, new IOException("empty content in response: " + snippet));
                        return;
                    }
                    if (content.isEmpty()) {
                        String snippet = respBody == null ? "" : respBody;
                        if (snippet.length() > 500) snippet = snippet.substring(0, 500) + "…";
                        Log.e(TAG, "content is empty string. raw(<=500)=" + snippet);
                        deliverFailure(cb, new IOException("content is empty: " + snippet));
                        return;
                    }
                    if (cb != null) {
                        try {
                            cb.onSuccess(new Result(content, respBody));
                        } catch (Throwable inner) {
                            Log.e(TAG, "callback.onSuccess threw", inner);
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "parse response failed", t);
                    deliverFailure(cb, t);
                }
            }
        });
    }

    /**
     * 从 OpenAI 兼容响应里抠出 {@code choices[0].message.content} 文本。
     * <p>兼容 content 的两种形态：
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
            JSONObject first = choices.optJSONObject(0);
            if (first == null) {
                Log.w(TAG, "choices[0] is not an object");
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
            Log.e(TAG, "extractMessageContent unexpected", t);
            return null;
        }
    }

    /**
     * 把失败透传给回调，并做好异常隔离 —— 任何 callback 自身抛出的
     * 异常都不能影响 OkHttp 线程。
     */
    private static void deliverFailure(@Nullable ResultCallback cb, @NonNull Throwable t) {
        if (cb == null) return;
        try {
            cb.onFailure(t);
        } catch (Throwable inner) {
            Log.e(TAG, "callback.onFailure threw", inner);
        }
    }

    // ------------------------------------------------------------
    // 类型
    // ------------------------------------------------------------

    public interface ResultCallback {
        void onSuccess(@NonNull Result result);

        void onFailure(@NonNull Throwable error);
    }

    public static final class Result {
        public final String content;
        public final String rawJson;

        public Result(@NonNull String content, @NonNull String rawJson) {
            this.content = content;
            this.rawJson = rawJson;
        }
    }
}

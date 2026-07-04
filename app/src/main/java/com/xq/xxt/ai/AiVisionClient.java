package com.xq.xxt.ai;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * 设计要点：
 * <ul>
 *   <li>OkHttp 单例，避免每次都重建连接池；</li>
 *   <li>请求/响应全程在 OkHttp 线程上回调，不阻塞调用者；</li>
 *   <li>JSON 手写而非引入 Gson/Moshi，把依赖控制到最小；</li>
 *   <li>Base64 编码走 {@link Base64#NO_WRAP}，符合 OpenAI 协议要求；</li>
 *   <li>支持运行时覆盖 API_KEY 和 BASE_URL，方便接入任何 OpenAI 兼容服务；</li>
 * </ul>
 */
public final class AiVisionClient {

    private static final String TAG = "AiVisionClient";

    public static final String DEFAULT_BASE_URL = "https://api.openai.com";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    private static volatile AiVisionClient INSTANCE;

    public static AiVisionClient get() {
        if (INSTANCE == null) {
            synchronized (AiVisionClient.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AiVisionClient();
                }
            }
        }
        return INSTANCE;
    }

    private final OkHttpClient http = new OkHttpClient.Builder()
            // Vision 接口响应通常 3-10s，给 60s 比较宽裕
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    // package-private：同包内（如 BackgroundAssistService）可直接读，避免再加 getter
    volatile String apiKey = "";
    volatile String baseUrl = DEFAULT_BASE_URL;
    volatile String model = DEFAULT_MODEL;

    private AiVisionClient() {
    }

    // ------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------

    public AiVisionClient withApiKey(@NonNull String key) {
        this.apiKey = key;
        return this;
    }

    public AiVisionClient withBaseUrl(@NonNull String url) {
        // 去掉尾部 /，避免拼路径时出现 //
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return this;
    }

    public AiVisionClient withModel(@NonNull String m) {
        this.model = m;
        return this;
    }

    // ------------------------------------------------------------
    // 公共 API
    // ------------------------------------------------------------

    /**
     * 异步提交一张截图给 Vision 模型。
     *
     * @param imagePath 本地图片绝对路径（通常是 /data/local/tmp/screencapture.png）
     * @param prompt    发送给模型的文本提示，例如 "这是题目，输出答案；如果是选择题只回 A/B/C/D"
     * @param cb        结果回调；可以为空
     */
    public void sendImageAsync(@NonNull String imagePath,
                               @NonNull String prompt,
                               @Nullable ResultCallback cb) {
        new Thread(() -> {
            try {
                String b64 = encodeFileToBase64(imagePath);
                String body = buildRequestBody(b64, prompt);
                doRequest(body, cb);
            } catch (Throwable t) {
                Log.e(TAG, "sendImageAsync prepare failed", t);
                if (cb != null) cb.onFailure(t);
            }
        }, "vision-prepare").start();
    }

    /**
     * 把本地文件编码为 OpenAI image_url 所需的 data url 形式
     * （{@code data:image/png;base64,xxxx}）。如果调用方只想拿裸 base64，用 {@link #encodeFileToBase64(String)}。
     */
    public static String encodeFileToDataUrl(@NonNull String path) throws IOException {
        byte[] bytes = Files.readAllBytes(new File(path).toPath());
        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        return "data:image/png;base64," + b64;
    }

    /**
     * 仅返回裸 base64 字符串。
     */
    public static String encodeFileToBase64(@NonNull String path) throws IOException {
        byte[] bytes = Files.readAllBytes(new File(path).toPath());
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    // ------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------

    private String buildRequestBody(@NonNull String base64Image, @NonNull String prompt) throws JSONException {
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
                // 限制输出长度：选择题答案不需要长篇大论；上游可按需覆盖
                .put("max_tokens", 1024)
                .toString();
    }

    private void doRequest(@NonNull String body, @Nullable ResultCallback cb) {
        if (apiKey == null || apiKey.isEmpty()) {
            if (cb != null) {
                cb.onFailure(new IllegalStateException("apiKey is empty; call withApiKey() first"));
            }
            return;
        }

        Request req = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body, MediaType.parse("application/json; charset=utf-8")))
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "network failure: " + e.getMessage());
                if (cb != null) cb.onFailure(e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    String respBody = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) {
                        String msg = "http " + r.code() + ": " + respBody;
                        Log.w(TAG, msg);
                        if (cb != null) {
                            cb.onFailure(new IOException(msg));
                        }
                        return;
                    }
                    String content = extractMessageContent(respBody);
                    if (cb != null) {
                        if (content == null) {
                            cb.onFailure(new IOException("empty content in response: " + respBody));
                        } else {
                            cb.onSuccess(new Result(content, respBody));
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "parse response failed", t);
                    if (cb != null) cb.onFailure(t);
                }
            }
        });
    }

    /**
     * 从标准 OpenAI 响应里抠出 assistant 的文本内容。容错处理：
     * - 顶层可能没有 choices 数组（部分兼容实现省略）；
     * - content 可能是 array（多模态）也可能是 string。
     */
    @Nullable
    private String extractMessageContent(@NonNull String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject first = choices.getJSONObject(0);
            JSONObject message = first.optJSONObject("message");
            if (message == null) {
                // 兼容某些实现把文本直接放在 text 字段
                return first.optString("text", null);
            }
            Object content = message.opt("content");
            if (content instanceof String) {
                return (String) content;
            }
            if (content instanceof JSONArray) {
                StringBuilder sb = new StringBuilder();
                JSONArray arr = (JSONArray) content;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject part = arr.optJSONObject(i);
                    if (part != null && "text".equals(part.optString("type"))) {
                        sb.append(part.optString("text"));
                    }
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            return null;
        } catch (JSONException e) {
            Log.w(TAG, "extractMessageContent parse failed: " + e.getMessage());
            return null;
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
        /** 模型返回的纯文本（已经过 content 抽取） */
        public final String content;
        /** 原始 JSON 字符串，便于上层做更复杂的解析 */
        public final String rawJson;

        public Result(@NonNull String content, @NonNull String rawJson) {
            this.content = content;
            this.rawJson = rawJson;
        }
    }
}
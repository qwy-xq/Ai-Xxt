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
import java.io.IOException;
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
 * <b>关键改动：</b>不再持有任何固定的 apiKey / baseUrl / model 字段 —— 这些信息全部
 * 由 {@link ConfigStore}（SharedPreferences）持有，每次 {@link #sendImageAsync}
 * 调用都重新读取。用户在 MainActivity 改了保存，下一次请求立刻生效。
 * <p>
 * OkHttpClient 仍然单例复用（避免反复重建连接池），但请求构造每次用最新配置。
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

                if (apiKey.isEmpty()) {
                    deliverFailure(cb, new IllegalStateException(
                            "apiKey is empty; please configure it in MainActivity first"));
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
     */
    @NonNull
    public static String encodeFileToDataUrl(@NonNull String path) throws IOException {
        byte[] bytes = Files.readAllBytes(new File(path).toPath());
        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        return "data:image/png;base64," + b64;
    }

    /**
     * 仅返回裸 base64 字符串。
     */
    @NonNull
    public static String encodeFileToBase64(@NonNull String path) throws IOException {
        byte[] bytes = Files.readAllBytes(new File(path).toPath());
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    // ------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------

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

    private void doRequest(@NonNull String baseUrl,
                           @NonNull String apiKey,
                           @NonNull String body,
                           @Nullable ResultCallback cb) {
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
                deliverFailure(cb, e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    String respBody = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) {
                        String msg = "http " + r.code() + ": " + respBody;
                        Log.w(TAG, msg);
                        deliverFailure(cb, new IOException(msg));
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
                    deliverFailure(cb, t);
                }
            }
        });
    }

    @Nullable
    private String extractMessageContent(@NonNull String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject first = choices.getJSONObject(0);
            JSONObject message = first.optJSONObject("message");
            if (message == null) {
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

    private static void deliverFailure(@Nullable ResultCallback cb, @NonNull Throwable t) {
        if (cb != null) {
            try {
                cb.onFailure(t);
            } catch (Throwable inner) {
                Log.w(TAG, "callback.onFailure threw", inner);
            }
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
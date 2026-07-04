package com.xq.xxt.ai;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * SharedPreferences 封装 —— 整个 App 共享一套配置。
 * <p>
 * 关键设计：{@link AiVisionClient} 和 {@link BackgroundAssistService} 每次需要
 * apiKey / baseUrl / model 时都通过 {@code ConfigStore} 重新读取，<b>绝不缓存过期值</b>。
 * 用户在 MainActivity 改了保存，下一次请求立刻生效。
 */
public final class ConfigStore {

    public static final String PREFS_NAME = "aixxt_config";

    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_MODEL = "model";

    /** 给 "用户没填" 时的兜底默认值 */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final SharedPreferences prefs;

    public ConfigStore(@NonNull Context ctx) {
        // 用 applicationContext 防止持有 Activity 引用导致泄漏
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    public String getApiKey() {
        String v = prefs.getString(KEY_API_KEY, "");
        return v == null ? "" : v.trim();
    }

    @NonNull
    public String getBaseUrl() {
        String v = prefs.getString(KEY_BASE_URL, "");
        if (v == null || v.trim().isEmpty()) return DEFAULT_BASE_URL;
        v = v.trim();
        // 去掉尾部 /，避免和 /v1/chat/completions 拼起来出现 //
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    @NonNull
    public String getModel() {
        String v = prefs.getString(KEY_MODEL, "");
        if (v == null || v.trim().isEmpty()) return DEFAULT_MODEL;
        return v.trim();
    }

    public void save(@NonNull String apiKey, @NonNull String baseUrl, @NonNull String model) {
        prefs.edit()
                .putString(KEY_API_KEY, apiKey.trim())
                .putString(KEY_BASE_URL, baseUrl.trim())
                .putString(KEY_MODEL, model.trim())
                .apply();
    }

    public boolean hasApiKey() {
        return !getApiKey().isEmpty();
    }
}
package com.xq.xxt.ai;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

/**
 * 主 Activity —— 配置入口。
 * <p>
 * 职责：
 * <ol>
 *   <li>读取 / 保存 {@link ConfigStore} 里的 apiKey / baseUrl / model；</li>
 *   <li>引导用户启动 {@link BackgroundAssistService}；</li>
 *   <li>Android 13+ 处理 {@code POST_NOTIFICATIONS} 运行时权限。</li>
 * </ol>
 */
public class MainActivity extends AppCompatActivity {

    private ConfigStore configStore;

    private TextInputEditText etApiKey;
    private TextInputEditText etBaseUrl;
    private TextInputEditText etModel;
    private MaterialButton btnSave;
    private MaterialButton btnStartService;
    private TextView tvStatus;

    private ActivityResultLauncher<String> notificationPermLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // 注入 Context 到 AiVisionClient（拿到 ConfigStore）
        AiVisionClient.init(this);
        configStore = new ConfigStore(this);

        // 系统栏 inset
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return insets;
        });

        etApiKey = findViewById(R.id.etApiKey);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etModel = findViewById(R.id.etModel);
        btnSave = findViewById(R.id.btnSave);
        btnStartService = findViewById(R.id.btnStartService);
        tvStatus = findViewById(R.id.tvStatus);

        // 用已存配置回填输入框
        etApiKey.setText(configStore.getApiKey());
        etBaseUrl.setText(configStore.getBaseUrl());
        etModel.setText(configStore.getModel());

        btnSave.setOnClickListener(v -> onSaveClicked());
        btnStartService.setOnClickListener(v -> onStartServiceClicked());

        // Android 13+ 通知权限请求器
        notificationPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    updateStatus();
                    actuallyStartService();
                });

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    // ------------------------------------------------------------
    // UI 事件
    // ------------------------------------------------------------

    private void onSaveClicked() {
        String apiKey = textOf(etApiKey);
        String baseUrl = textOf(etBaseUrl);
        String model = textOf(etModel);

        if (TextUtils.isEmpty(apiKey)) {
            etApiKey.setError("API Key 不能为空");
            etApiKey.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(baseUrl)) {
            baseUrl = ConfigStore.DEFAULT_BASE_URL;
            etBaseUrl.setText(baseUrl);
        }
        if (TextUtils.isEmpty(model)) {
            model = ConfigStore.DEFAULT_MODEL;
            etModel.setText(model);
        }

        configStore.save(apiKey, baseUrl, model);
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void onStartServiceClicked() {
        // 没配 apiKey 先提示
        if (!configStore.hasApiKey()) {
            Toast.makeText(this, "请先填写并保存 API Key", Toast.LENGTH_SHORT).show();
            etApiKey.requestFocus();
            return;
        }

        // Android 13+ 需要 POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS);
            if (granted != PackageManager.PERMISSION_GRANTED) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        actuallyStartService();
    }

    private void actuallyStartService() {
        Intent intent = new Intent(this, BackgroundAssistService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "后台服务已启动", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    // ------------------------------------------------------------
    // 状态展示
    // ------------------------------------------------------------

    private void updateStatus() {
        boolean hasKey = configStore.hasApiKey();
        String model = configStore.getModel();
        tvStatus.setText("API Key: " + (hasKey ? "已配置" : "未配置")
                + "\nModel: " + model
                + "\nService: " + (BackgroundAssistService.isRunning() ? "运行中" : "未启动"));
    }

    @NonNull
    private static String textOf(@NonNull TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }
}
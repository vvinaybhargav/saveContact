package com.example.callsaver;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private EditText etOpenAiKey;
    private EditText etDeepgramKey;
    private EditText etUserName;
    private SwitchMaterial switchAutoTranscribe;
    private Button btnSave;
    private Button btnAnalytics;
    private Button btnClearCache;
    private TextView tvLogs;
    private TextView btnClearLogs;
    private TextView btnRefreshLogs;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);

        // Bind views
        Toolbar toolbar = findViewById(R.id.toolbar_settings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etOpenAiKey = findViewById(R.id.settings_openai_key);
        etDeepgramKey = findViewById(R.id.settings_deepgram_key);
        etUserName = findViewById(R.id.settings_user_name);
        switchAutoTranscribe = findViewById(R.id.switch_auto_transcribe);
        btnSave = findViewById(R.id.btn_settings_save_keys);
        btnAnalytics = findViewById(R.id.btn_settings_analytics);
        btnClearCache = findViewById(R.id.btn_settings_clear_cache);
        tvLogs = findViewById(R.id.tv_diagnostic_logs);
        btnClearLogs = findViewById(R.id.btn_clear_logs);
        btnRefreshLogs = findViewById(R.id.btn_refresh_logs);

        // Load preferences
        etOpenAiKey.setText(prefs.getString("openai_api_key", ""));
        etDeepgramKey.setText(prefs.getString("deepgram_api_key", ""));
        etUserName.setText(prefs.getString("user_full_name", ""));
        switchAutoTranscribe.setChecked(prefs.getBoolean("auto_transcribe_background", true));

        // Setup save action
        btnSave.setOnClickListener(v -> {
            String openAi = etOpenAiKey.getText().toString().trim();
            String deepgram = etDeepgramKey.getText().toString().trim();
            String userName = etUserName.getText().toString().trim();
            boolean autoTranscribe = switchAutoTranscribe.isChecked();

            prefs.edit()
                    .putString("openai_api_key", openAi)
                    .putString("deepgram_api_key", deepgram)
                    .putString("user_full_name", userName)
                    .putBoolean("auto_transcribe_background", autoTranscribe)
                    .apply();

            Toast.makeText(this, "Settings saved successfully!", Toast.LENGTH_SHORT).show();
        });

        // Setup detailed analytics button
        btnAnalytics.setOnClickListener(v -> {
            Intent intent = new Intent(this, AnalyticsActivity.class);
            startActivity(intent);
        });

        // Setup clear cache button
        updateCacheSizeButton();
        btnClearCache.setOnClickListener(v -> {
            File cacheDir = CallRecorderService.recordingsDir(this);
            File[] files = cacheDir.listFiles();
            int deletedCount = 0;
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.delete()) {
                        deletedCount++;
                    }
                }
            }
            Toast.makeText(this, "Cleared " + deletedCount + " cached files.", Toast.LENGTH_SHORT).show();
            updateCacheSizeButton();
        });

        // Setup diagnostic logs actions
        loadDiagnosticLogs();
        btnRefreshLogs.setOnClickListener(v -> loadDiagnosticLogs());
        btnClearLogs.setOnClickListener(v -> {
            DebugLogger.clearLogs(this);
            loadDiagnosticLogs();
            Toast.makeText(this, "Logs cleared.", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateCacheSizeButton() {
        File cacheDir = CallRecorderService.recordingsDir(this);
        File[] files = cacheDir.listFiles();
        long totalSize = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    totalSize += f.length();
                }
            }
        }

        String sizeText;
        if (totalSize < 1024) {
            sizeText = String.format(Locale.getDefault(), "%d B", totalSize);
        } else if (totalSize < 1024 * 1024) {
            sizeText = String.format(Locale.getDefault(), "%.1f KB", totalSize / 1024.0);
        } else {
            sizeText = String.format(Locale.getDefault(), "%.1f MB", totalSize / (1024.0 * 1024.0));
        }

        btnClearCache.setText("Clear Cache (" + sizeText + ")");
    }

    private void loadDiagnosticLogs() {
        String logContent = DebugLogger.readLogs(this).trim();
        if (logContent.isEmpty()) {
            tvLogs.setText("[No diagnostic logs recorded yet]");
        } else {
            tvLogs.setText(logContent);
        }
    }
}

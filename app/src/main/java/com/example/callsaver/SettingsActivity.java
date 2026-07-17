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
    private EditText etUserInterests;
    private EditText etRecordingFolder;
    private static final int REQ_CODE_INTERESTS_SPEECH = 700;
    private static final int REQ_CODE_BROWSE_FOLDER = 701;
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
        etUserInterests = findViewById(R.id.settings_user_interests);
        com.google.android.material.textfield.TextInputLayout tilInterests = findViewById(R.id.til_settings_interests);
        etRecordingFolder = findViewById(R.id.settings_recording_folder);
        Button btnBrowseFolder = findViewById(R.id.btn_browse_recording_folder);
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
        etUserInterests.setText(prefs.getString("user_talking_points", ""));
        etRecordingFolder.setText(prefs.getString("custom_recording_folder", ""));
        switchAutoTranscribe.setChecked(prefs.getBoolean("auto_transcribe_background", true));

        // Setup save action
        btnSave.setOnClickListener(v -> {
            String openAi = etOpenAiKey.getText().toString().trim();
            String deepgram = etDeepgramKey.getText().toString().trim();
            String userName = etUserName.getText().toString().trim();
            String interests = etUserInterests.getText().toString().trim();
            String recordingFolder = etRecordingFolder.getText().toString().trim();
            boolean autoTranscribe = switchAutoTranscribe.isChecked();

            prefs.edit()
                    .putString("openai_api_key", openAi)
                    .putString("deepgram_api_key", deepgram)
                    .putString("user_full_name", userName)
                    .putString("user_talking_points", interests)
                    .putString("custom_recording_folder", recordingFolder)
                    .putBoolean("auto_transcribe_background", autoTranscribe)
                    .apply();

            Toast.makeText(this, "Settings saved successfully!", Toast.LENGTH_SHORT).show();
        });

        // Browse for the recording folder using the system folder picker.
        btnBrowseFolder.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivityForResult(intent, REQ_CODE_BROWSE_FOLDER);
            } catch (Exception e) {
                Toast.makeText(this, "No file picker available on this device.", Toast.LENGTH_SHORT).show();
            }
        });

        // Mic input for My Interests
        if (tilInterests != null) {
            tilInterests.setEndIconOnClickListener(v -> {
                Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your interests...");
                try {
                    startActivityForResult(intent, REQ_CODE_INTERESTS_SPEECH);
                } catch (Exception e) {
                    Toast.makeText(this, "Speech recognition is not supported.", Toast.LENGTH_SHORT).show();
                }
            });
        }

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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_INTERESTS_SPEECH && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> results = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String spoken = results.get(0);
                String current = etUserInterests.getText().toString().trim();
                etUserInterests.setText(current.isEmpty() ? spoken : current + " " + spoken);
                etUserInterests.setSelection(etUserInterests.getText().length());
            }
        } else if (requestCode == REQ_CODE_BROWSE_FOLDER && resultCode == RESULT_OK && data != null) {
            android.net.Uri treeUri = data.getData();
            if (treeUri != null) {
                String path = pathFromTreeUri(treeUri);
                if (path != null) {
                    etRecordingFolder.setText(path);
                } else {
                    Toast.makeText(this, "That folder isn't on internal storage - type the path manually instead.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * Converts a SAF tree Uri picked via ACTION_OPEN_DOCUMENT_TREE into a plain
     * filesystem path, so the scanner (which reads java.io.File directly, relying on
     * the app's All-Files-Access permission) can use it. Only works for folders on
     * the device's primary internal storage - external SD cards aren't resolvable
     * to a raw path this way, so the user is told to type it manually in that case.
     */
    private String pathFromTreeUri(android.net.Uri treeUri) {
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            String[] split = docId.split(":");
            if (split.length == 0) return null;
            String type = split[0];
            if (!"primary".equalsIgnoreCase(type)) return null;
            String relativePath = split.length > 1 ? split[1] : "";
            String base = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            return relativePath.isEmpty() ? base : base + "/" + relativePath;
        } catch (Exception e) {
            return null;
        }
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

package com.example.callsaver;

import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.SharedPreferences;
import android.widget.AdapterView;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.chip.Chip;

import android.media.MediaPlayer;
import android.widget.SeekBar;
import android.widget.ProgressBar;
import android.widget.SpinnerAdapter;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import java.util.Calendar;
import org.json.JSONObject;
import org.json.JSONArray;
import android.widget.ImageView;
import java.io.File;
import android.os.Handler;

public class SaveContactActivity extends AppCompatActivity {

    private String phoneNumber;
    private long callTimestamp;
    private int callDuration;
    
    private EditText etCompanyName;
    private EditText etTags;
    private EditText etNotes;
    private EditText etRecruiterName;
    private EditText etCandidateName;
    private EditText etAppliedRole;
    private EditText etTentativeSchedule;
    private EditText etNoticePeriod;
    private EditText etMainAgenda;
    private EditText etNextSteps;
    private Spinner spinnerRound;
    private Spinner spinnerAccount;
    private DatabaseHelper dbHelper;
    private static final int REQ_CODE_SPEECH_INPUT = 1001;

    // Call Recording and Whisper API support
    private File recordingFile;
    private MediaPlayer mediaPlayer;
    private Handler playerHandler = new Handler();
    private boolean isPlaying = false;

    private View llRecordingPanel;
    private ImageView ivPlayPause;
    private SeekBar sbRecordingProgress;
    private TextView tvPlayerTime;
    private ProgressBar pbTranscribe;
    private TextView tvTranscriptionStatus;
    
    private TextView tvToggleApiKey;
    private View llApiKeyContainer;
    private EditText etDeepgramApiKey;
    private EditText etOpenAiApiKey;
    private Button btnSaveApiKey;
    private Button btnAutoTranscribe;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Show over lockscreen if device is locked
        configureShowWhenLocked();
        
        setContentView(R.layout.activity_save_contact);

        dbHelper = new DatabaseHelper(this);

        phoneNumber = getIntent().getStringExtra("phone_number");
        callTimestamp = getIntent().getLongExtra("timestamp", System.currentTimeMillis());
        callDuration = getIntent().getIntExtra("duration", 0);

        DebugLogger.log(this, "[Activity] Opened for number: " + phoneNumber + ", duration: " + callDuration + "s, timestamp: " + callTimestamp);

        if (phoneNumber == null) {
            finish();
            return;
        }

        TextView tvPhoneNumber = findViewById(R.id.tv_phone_number);
        etCompanyName = findViewById(R.id.et_company_name);
        etRecruiterName = findViewById(R.id.et_recruiter_name);
        etTags = findViewById(R.id.et_tags);
        etNotes = findViewById(R.id.et_notes);
        spinnerRound = findViewById(R.id.spinner_round);
        spinnerAccount = findViewById(R.id.spinner_account);

        etCandidateName = findViewById(R.id.et_candidate_name);
        etAppliedRole = findViewById(R.id.et_applied_role);
        etTentativeSchedule = findViewById(R.id.et_tentative_schedule);
        etNoticePeriod = findViewById(R.id.et_notice_period);
        etMainAgenda = findViewById(R.id.et_main_agenda);
        etNextSteps = findViewById(R.id.et_next_steps);

        if (etTentativeSchedule != null) {
            etTentativeSchedule.setOnClickListener(v -> showDateTimePicker(etTentativeSchedule));
        }
        
        TextInputLayout tilNotes = findViewById(R.id.til_notes);

        tilNotes.setEndIconOnClickListener(v -> {
            Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault());
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak note...");
            try {
                startActivityForResult(intent, REQ_CODE_SPEECH_INPUT);
            } catch (Exception e) {
                Toast.makeText(this, "Speech recognition is not supported on this device.", Toast.LENGTH_SHORT).show();
            }
        });

        Button btnDismiss = findViewById(R.id.btn_dismiss);
        Button btnSaveBoth = findViewById(R.id.btn_save_both);
        View rootLayout = findViewById(R.id.root_layout);

        tvPhoneNumber.setText(phoneNumber);

        // Bind spinner data
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.round_statuses, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRound.setAdapter(adapter);

        // Bind Recording & Transcription views
        llRecordingPanel = findViewById(R.id.ll_recording_panel);
        ivPlayPause = findViewById(R.id.iv_play_pause);
        sbRecordingProgress = findViewById(R.id.sb_recording_progress);
        tvPlayerTime = findViewById(R.id.tv_player_time);
        pbTranscribe = findViewById(R.id.pb_transcribe);
        btnAutoTranscribe = findViewById(R.id.btn_auto_transcribe);

        tvToggleApiKey = findViewById(R.id.tv_toggle_api_key);
        llApiKeyContainer = findViewById(R.id.ll_api_key_container);
        etDeepgramApiKey = findViewById(R.id.et_deepgram_api_key);
        etOpenAiApiKey = findViewById(R.id.et_openai_api_key);
        btnSaveApiKey = findViewById(R.id.btn_save_api_key);

        tvTranscriptionStatus = findViewById(R.id.tv_transcription_status);

        // Setup API Key preferences and UI controls
        SharedPreferences apiPrefs = getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);
        String savedKey = apiPrefs.getString("deepgram_api_key", "");
        etDeepgramApiKey.setText(savedKey);
        etOpenAiApiKey.setText(apiPrefs.getString("openai_api_key", ""));

        tvToggleApiKey.setText("▼ Settings: API Keys");
        tvToggleApiKey.setOnClickListener(v -> {
            if (llApiKeyContainer.getVisibility() == View.VISIBLE) {
                llApiKeyContainer.setVisibility(View.GONE);
                tvToggleApiKey.setText("▼ Settings: API Keys");
            } else {
                llApiKeyContainer.setVisibility(View.VISIBLE);
                tvToggleApiKey.setText("▲ Settings: API Keys");
            }
        });

        btnSaveApiKey.setOnClickListener(v -> {
            String dgKey = etDeepgramApiKey.getText().toString().trim();
            String oaKey = etOpenAiApiKey.getText().toString().trim();
            apiPrefs.edit()
                    .putString("deepgram_api_key", dgKey)
                    .putString("openai_api_key", oaKey)
                    .apply();
            Toast.makeText(this, "API Keys saved successfully!", Toast.LENGTH_SHORT).show();
            llApiKeyContainer.setVisibility(View.GONE);
            tvToggleApiKey.setText("▼ Settings: API Keys");
        });

        // Set up auto-transcribe action
        btnAutoTranscribe.setOnClickListener(v -> {
            pbTranscribe.setVisibility(View.VISIBLE);
            if (tvTranscriptionStatus != null) {
                tvTranscriptionStatus.setVisibility(View.VISIBLE);
                tvTranscriptionStatus.setText("✨ Transcribing call recording via Deepgram...");
            }
            btnAutoTranscribe.setEnabled(false);

            // Notify other app components that we are actively transcribing
            SharedPreferences prefs = getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);
            prefs.edit()
                    .putBoolean("is_transcribing", true)
                    .putString("transcribing_number", phoneNumber)
                    .apply();

            DebugLogger.log(this, "[Activity] Starting transcription for file: " + (recordingFile != null ? recordingFile.getName() : "null"));

            Transcriber.transcribeCallRecording(this, recordingFile, new Transcriber.TranscriptionCallback() {
                @Override
                public void onSuccess(String text) {
                    if (text == null || text.trim().isEmpty()) {
                        prefs.edit().putBoolean("is_transcribing", false).remove("transcribing_number").apply();
                        pbTranscribe.setVisibility(View.GONE);
                        btnAutoTranscribe.setEnabled(true);
                        return;
                    }

                    // Check OpenAI API Key
                    String openAiKey = getSharedPreferences("CallSaverPrefs", MODE_PRIVATE).getString("openai_api_key", "").trim();
                    if (openAiKey.isEmpty()) {
                        // Fallback: No OpenAI key -> Save transcription raw
                        prefs.edit().putBoolean("is_transcribing", false).remove("transcribing_number").apply();
                        pbTranscribe.setVisibility(View.GONE);
                        if (tvTranscriptionStatus != null) {
                            tvTranscriptionStatus.setText("✅ Transcribed successfully!");
                        }
                        btnAutoTranscribe.setEnabled(true);
                        
                        String currentNotes = etNotes.getText().toString().trim();
                        etNotes.setText(currentNotes.isEmpty() ? text : currentNotes + "\n" + text);
                        Toast.makeText(SaveContactActivity.this, "Transcription success! Saved raw.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Query OpenAI
                    if (tvTranscriptionStatus != null) {
                        tvTranscriptionStatus.setText("✨ Extracting fields using OpenAI...");
                    }
                    OpenAiClient.extractFields(SaveContactActivity.this, text, new OpenAiClient.OpenAiCallback() {
                        @Override
                        public void onSuccess(JSONObject result) {
                            prefs.edit().putBoolean("is_transcribing", false).remove("transcribing_number").apply();
                            pbTranscribe.setVisibility(View.GONE);
                            if (tvTranscriptionStatus != null) {
                                tvTranscriptionStatus.setText("✅ Processed successfully!");
                            }
                            btnAutoTranscribe.setEnabled(true);
                            
                            try {
                                if (result.has("candidate_name") && !result.isNull("candidate_name")) {
                                    etCandidateName.setText(result.getString("candidate_name"));
                                }
                                if (result.has("company_name") && !result.isNull("company_name")) {
                                    etCompanyName.setText(result.getString("company_name"));
                                }
                                if (result.has("applied_role") && !result.isNull("applied_role")) {
                                    etAppliedRole.setText(result.getString("applied_role"));
                                }
                                if (result.has("present_round") && !result.isNull("present_round")) {
                                    setSpinnerSelection(spinnerRound, result.getString("present_round"));
                                }
                                if (result.has("tentative_schedule") && !result.isNull("tentative_schedule")) {
                                    etTentativeSchedule.setText(result.getString("tentative_schedule"));
                                }
                                if (result.has("notice_period") && !result.isNull("notice_period")) {
                                    etNoticePeriod.setText(result.getString("notice_period"));
                                }
                                if (result.has("main_agenda") && !result.isNull("main_agenda")) {
                                    etMainAgenda.setText(result.getString("main_agenda"));
                                }
                                if (result.has("next_steps") && !result.isNull("next_steps")) {
                                    etNextSteps.setText(result.getString("next_steps"));
                                }
                                
                                if (result.has("key_discussion_points")) {
                                    JSONArray arr = result.getJSONArray("key_discussion_points");
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < arr.length(); i++) {
                                        sb.append("• ").append(arr.getString(i)).append("\n");
                                    }
                                    etNotes.setText(sb.toString().trim());
                                }
                                Toast.makeText(SaveContactActivity.this, "AI fields updated successfully!", Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                DebugLogger.log(SaveContactActivity.this, "Failed to parse OpenAI fields: " + e.getMessage());
                                etNotes.setText(text);
                                Toast.makeText(SaveContactActivity.this, "AI parsing failed, pre-filled raw transcription.", Toast.LENGTH_LONG).show();
                            }
                        }

                        @Override
                        public void onError(String error) {
                            prefs.edit().putBoolean("is_transcribing", false).remove("transcribing_number").apply();
                            pbTranscribe.setVisibility(View.GONE);
                            if (tvTranscriptionStatus != null) {
                                tvTranscriptionStatus.setText("⚠️ OpenAI error: " + error);
                            }
                            btnAutoTranscribe.setEnabled(true);
                            etNotes.setText(text);
                            Toast.makeText(SaveContactActivity.this, "OpenAI failed: " + error + ". Saved raw.", Toast.LENGTH_LONG).show();
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    prefs.edit()
                            .putBoolean("is_transcribing", false)
                            .remove("transcribing_number")
                            .apply();

                    pbTranscribe.setVisibility(View.GONE);
                    if (tvTranscriptionStatus != null) {
                        tvTranscriptionStatus.setText("❌ Error: " + error);
                    }
                    btnAutoTranscribe.setEnabled(true);
                    DebugLogger.log(SaveContactActivity.this, "[Activity] Transcription failed: " + error);
                    Toast.makeText(SaveContactActivity.this, error, Toast.LENGTH_LONG).show();
                }
            });
        });

        // Scan for recording file with a delayed progress update so the user knows it's searching
        llRecordingPanel.setVisibility(View.VISIBLE);
        if (tvTranscriptionStatus != null) {
            tvTranscriptionStatus.setVisibility(View.VISIBLE);
            tvTranscriptionStatus.setText("🔍 Locating call recording file...");
        }
        pbTranscribe.setVisibility(View.VISIBLE);
        btnAutoTranscribe.setVisibility(View.GONE);

        // Hide player controls until file is verified
        final View playBtn = findViewById(R.id.iv_play_pause);
        if (playBtn != null && playBtn.getParent() instanceof View) {
            ((View) playBtn.getParent()).setVisibility(View.GONE);
        }

        DebugLogger.log(this, "[Activity] Scanning call recordings for caller " + phoneNumber + ", timestamp=" + callTimestamp);

        new Handler().postDelayed(() -> {
            recordingFile = CallRecordingScanner.findLatestCallRecording(this, phoneNumber, callTimestamp);
            if (recordingFile == null) {
                DebugLogger.log(this, "[Activity] Call recording timestamp search empty, trying fallback matching...");
                recordingFile = CallRecordingScanner.findLatestCallRecording(this, phoneNumber, 0);
            }

            if (recordingFile != null) {
                DebugLogger.log(this, "[Activity] Call recording found: " + recordingFile.getAbsolutePath() + " (" + recordingFile.length() + " bytes)");
                setupAudioPlayer();
                // Show player controls
                if (playBtn != null && playBtn.getParent() instanceof View) {
                    ((View) playBtn.getParent()).setVisibility(View.VISIBLE);
                }
                btnAutoTranscribe.setVisibility(View.VISIBLE);

                if (!savedKey.isEmpty()) {
                    btnAutoTranscribe.performClick();
                } else {
                    pbTranscribe.setVisibility(View.GONE);
                    if (tvTranscriptionStatus != null) {
                        tvTranscriptionStatus.setText("✅ Recording located. Tap Auto-Transcribe.");
                    }
                }
            } else {
                DebugLogger.log(this, "[Activity] No matching recording file found inside storage.");
                pbTranscribe.setVisibility(View.GONE);
                if (tvTranscriptionStatus != null) {
                    String msg;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                            && !android.os.Environment.isExternalStorageManager()) {
                        msg = "❌ No recording found. Grant 'All files access' in Settings so the app can read the OnePlus call recordings.";
                    } else {
                        msg = "❌ No recording found for this call. Enable call recording (auto-record) in the OnePlus Phone app, then call again.";
                    }
                    tvTranscriptionStatus.setText(msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            }
        }, 1500); // 1.5 seconds delay guarantees file system synchronization

        // Fetch accounts
        List<String> accountNames = new ArrayList<>();
        List<Account> emailAccounts = new ArrayList<>();

        // Add default local device
        accountNames.add("Local Device");
        emailAccounts.add(null);

        try {
            AccountManager am = AccountManager.get(this);
            Account[] accounts = am.getAccounts();
            for (Account acct : accounts) {
                if (acct.name != null && acct.name.contains("@")) {
                    accountNames.add(acct.name + " (" + acct.type + ")");
                    emailAccounts.add(acct);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        ArrayAdapter<String> accountAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, accountNames);
        accountAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAccount.setAdapter(accountAdapter);

        // Restore preference
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String preferredName = prefs.getString("preferred_contact_account_name", null);
        String preferredType = prefs.getString("preferred_contact_account_type", null);

        if (preferredName != null && preferredType != null) {
            for (int i = 0; i < emailAccounts.size(); i++) {
                Account acc = emailAccounts.get(i);
                if (acc != null && preferredName.equals(acc.name) && preferredType.equals(acc.type)) {
                    spinnerAccount.setSelection(i);
                    break;
                }
            }
        }

        // Save preference when user selects an option
        spinnerAccount.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Account selected = emailAccounts.get(position);
                SharedPreferences.Editor editor = prefs.edit();
                if (selected != null) {
                    editor.putString("preferred_contact_account_name", selected.name);
                    editor.putString("preferred_contact_account_type", selected.type);
                } else {
                    editor.remove("preferred_contact_account_name");
                    editor.remove("preferred_contact_account_type");
                }
                editor.apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Pre-fill fields if caller is already logged in the SQLite DB
        JobCall existingCall = dbHelper.getJobCallByNumber(this, phoneNumber);
        if (existingCall != null) {
            etCompanyName.setText(existingCall.getCompanyName());
            etRecruiterName.setText(existingCall.getRecruiterName());
            etTags.setText(existingCall.getTags());
            etNotes.setText(existingCall.getNotes());
            if (existingCall.getRoundStatus() != null) {
                int pos = adapter.getPosition(existingCall.getRoundStatus());
                spinnerRound.setSelection(pos >= 0 ? pos : 0);
            }
            etCandidateName.setText(existingCall.getCandidateName());
            etAppliedRole.setText(existingCall.getAppliedRole());
            etTentativeSchedule.setText(existingCall.getTentativeSchedule());
            etNoticePeriod.setText(existingCall.getNoticePeriod());
            etMainAgenda.setText(existingCall.getMainAgenda());
            etNextSteps.setText(existingCall.getNextSteps());
        }

        // Dismiss when clicking outside the dialog card
        rootLayout.setOnClickListener(v -> finish());

        btnDismiss.setOnClickListener(v -> finish());

        btnSaveBoth.setOnClickListener(v -> {
            String company = etCompanyName.getText().toString().trim();
            String recruiter = etRecruiterName.getText().toString().trim();
            String tags = etTags.getText().toString().trim();
            String notes = etNotes.getText().toString().trim();
            String round = spinnerRound.getSelectedItem().toString();

            String candidate = etCandidateName.getText().toString().trim();
            String role = etAppliedRole.getText().toString().trim();
            String schedule = etTentativeSchedule.getText().toString().trim();
            String notice = etNoticePeriod.getText().toString().trim();
            String agenda = etMainAgenda.getText().toString().trim();
            String nextStepsVal = etNextSteps.getText().toString().trim();

            // Deduplicate check
            JobCall existing = null;
            if (!company.isEmpty()) {
                existing = dbHelper.getJobCallByCompany(company);
            }

            if (existing != null) {
                // Link number/name to existing company lead
                dbHelper.linkPhoneToJob(existing.getId(), phoneNumber, recruiter);
                if (!notes.isEmpty()) {
                    dbHelper.insertNote(existing.getId(), notes, System.currentTimeMillis());
                }
                
                // Update candidate fields on the existing entry
                existing.setCandidateName(candidate);
                existing.setAppliedRole(role);
                existing.setTentativeSchedule(schedule);
                existing.setNoticePeriod(notice);
                existing.setMainAgenda(agenda);
                existing.setKeyDiscussionPoints(notes);
                existing.setNextSteps(nextStepsVal);
                existing.setRoundStatus(round);
                if (!recruiter.isEmpty()) {
                    existing.setRecruiterName(recruiter);
                }
                dbHelper.updateJobCall(existing);

                Toast.makeText(SaveContactActivity.this, "Linked to existing company " + existing.getCompanyName(), Toast.LENGTH_LONG).show();
                finish();
            } else {
                // Save new local job calls database
                JobCall call = new JobCall(phoneNumber, company, round, tags, notes, callDuration, callTimestamp);
                call.setRecruiterName(recruiter);
                call.setCandidateName(candidate);
                call.setAppliedRole(role);
                call.setTentativeSchedule(schedule);
                call.setNoticePeriod(notice);
                call.setMainAgenda(agenda);
                call.setKeyDiscussionPoints(notes);
                call.setNextSteps(nextStepsVal);

                long id = dbHelper.insertJobCall(call);

                if (id != -1) {
                    Toast.makeText(SaveContactActivity.this, "Log saved to tracker!", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(SaveContactActivity.this, R.string.msg_contact_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Request keyboard focus automatically
        etCompanyName.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        
        // Force slide-up keyboard helper
        etCompanyName.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etCompanyName, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);

        View tvManualRecording = findViewById(R.id.tv_manual_recording);
        if (tvManualRecording != null) {
            tvManualRecording.setOnClickListener(v -> showManualRecordingPickerDialog());
        }
    }

    private void showManualRecordingPickerDialog() {
        File[] candidateDirs = new File[] {
                new File(android.os.Environment.getExternalStorageDirectory(), "Music/Recordings/Call Recordings"),
                new File(android.os.Environment.getExternalStorageDirectory(), "Recordings/Call"),
                new File(android.os.Environment.getExternalStorageDirectory(), "Recordings/Call Recordings"),
                new File(android.os.Environment.getExternalStorageDirectory(), "Recordings"),
                new File(android.os.Environment.getExternalStorageDirectory(), "Music/Recordings")
        };

        final List<File> audioFiles = new ArrayList<>();
        for (File dir : candidateDirs) {
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile()) {
                            String name = f.getName().toLowerCase();
                            boolean isAudio = name.endsWith(".mp3") || name.endsWith(".wav") || 
                                              name.endsWith(".m4a") || name.endsWith(".amr") || 
                                              name.endsWith(".aac") || name.endsWith(".ogg") ||
                                              name.endsWith(".mp4");
                            if (isAudio) {
                                audioFiles.add(f);
                            }
                        }
                    }
                }
            }
        }

        // Sort by date modified DESC (newest first)
        Collections.sort(audioFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        if (audioFiles.isEmpty()) {
            Toast.makeText(this, "No call recording files found on device storage.", Toast.LENGTH_LONG).show();
            return;
        }

        // Limit listing to top 50 recent files
        int limit = Math.min(audioFiles.size(), 50);
        String[] fileNames = new String[limit];
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
        for (int i = 0; i < limit; i++) {
            File f = audioFiles.get(i);
            String dateStr = sdf.format(new Date(f.lastModified()));
            double sizeMb = f.length() / (1024.0 * 1024.0);
            fileNames[i] = f.getName() + "\n(" + dateStr + " · " + String.format(Locale.getDefault(), "%.2f MB", sizeMb) + ")";
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select Call Recording File")
                .setItems(fileNames, (dialog, which) -> {
                    File selectedFile = audioFiles.get(which);
                    recordingFile = selectedFile;
                    
                    DebugLogger.log(this, "[Activity] Manually selected recording: " + selectedFile.getAbsolutePath());
                    
                    // Show recording player controls and auto transcribe
                    llRecordingPanel.setVisibility(View.VISIBLE);
                    setupAudioPlayer();
                    
                    final View playBtn = findViewById(R.id.iv_play_pause);
                    if (playBtn != null && playBtn.getParent() instanceof View) {
                        ((View) playBtn.getParent()).setVisibility(View.VISIBLE);
                    }
                    
                    View autoTranscribeBtn = findViewById(R.id.btn_auto_transcribe);
                    if (autoTranscribeBtn != null) {
                        autoTranscribeBtn.setVisibility(View.VISIBLE);
                        
                        SharedPreferences apiPrefs = getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);
                        String savedKey = apiPrefs.getString("deepgram_api_key", "");
                        if (!savedKey.isEmpty()) {
                            autoTranscribeBtn.performClick();
                        } else {
                            pbTranscribe.setVisibility(View.GONE);
                            if (tvTranscriptionStatus != null) {
                                tvTranscriptionStatus.setText("✅ Recording located. Tap Auto-Transcribe.");
                            }
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void configureShowWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    private boolean saveContactDirectly(String name, String phoneNumber) {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String accName = prefs.getString("preferred_contact_account_name", null);
        String accType = prefs.getString("preferred_contact_account_type", null);

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        int rawContactInsertIndex = ops.size();
        ops.add(ContentProviderOperation.newInsert(android.provider.ContactsContract.RawContacts.CONTENT_URI)
                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, accType)
                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, accName)
                .build());

        ops.add(ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(android.provider.ContactsContract.Data.MIMETYPE, 
                        android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build());

        ops.add(ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(android.provider.ContactsContract.Data.MIMETYPE, 
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE, 
                        android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build());

        try {
            getContentResolver().applyBatch(android.provider.ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_SPEECH_INPUT && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                String spokenText = result.get(0);
                String currentText = etNotes.getText().toString();
                if (currentText.trim().isEmpty()) {
                    etNotes.setText(spokenText);
                } else {
                    etNotes.setText(currentText + " " + spokenText);
                }
                etNotes.setSelection(etNotes.getText().length());
            }
        }
    }

    private void setupAudioPlayer() {
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(recordingFile.getAbsolutePath());
            mediaPlayer.prepare();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error preparing player: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        int duration = mediaPlayer.getDuration();
        sbRecordingProgress.setMax(duration);
        tvPlayerTime.setText(formatTime(0) + " / " + formatTime(duration));

        ivPlayPause.setOnClickListener(v -> {
            if (isPlaying) {
                pauseAudio();
            } else {
                playAudio();
            }
        });

        sbRecordingProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mediaPlayer.seekTo(progress);
                    tvPlayerTime.setText(formatTime(progress) + " / " + formatTime(mediaPlayer.getDuration()));
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            isPlaying = false;
            ivPlayPause.setImageResource(android.R.drawable.ic_media_play);
            sbRecordingProgress.setProgress(0);
            tvPlayerTime.setText(formatTime(0) + " / " + formatTime(mediaPlayer.getDuration()));
        });
    }

    private void playAudio() {
        if (mediaPlayer != null) {
            mediaPlayer.start();
            isPlaying = true;
            ivPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            updateSeekBarProgress();
        }
    }

    private void pauseAudio() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
            isPlaying = false;
            ivPlayPause.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    private void updateSeekBarProgress() {
        if (mediaPlayer != null && isPlaying) {
            int current = mediaPlayer.getCurrentPosition();
            sbRecordingProgress.setProgress(current);
            tvPlayerTime.setText(formatTime(current) + " / " + formatTime(mediaPlayer.getDuration()));
            playerHandler.postDelayed(this::updateSeekBarProgress, 250);
        }
    }

    private String formatTime(int ms) {
        int sec = ms / 1000;
        int min = sec / 60;
        sec = sec % 60;
        return String.format("%02d:%02d", min, sec);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void showDateTimePicker(EditText et) {
        Calendar calendar = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            calendar.set(Calendar.YEAR, year);
            calendar.set(Calendar.MONTH, month);
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);

            new TimePickerDialog(this, (timeView, hourOfDay, minute) -> {
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
                calendar.set(Calendar.MINUTE, minute);

                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd 'at' hh:mm a", Locale.getDefault());
                et.setText(format.format(calendar.getTime()));
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show();

        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void setSpinnerSelection(Spinner spinner, String value) {
        if (value == null || value.trim().isEmpty()) return;
        SpinnerAdapter adapter = spinner.getAdapter();
        if (adapter == null) return;
        String lowerVal = value.toLowerCase().trim();
        for (int i = 0; i < adapter.getCount(); i++) {
            String item = adapter.getItem(i).toString().toLowerCase();
            if (item.contains(lowerVal) || lowerVal.contains(item)) {
                spinner.setSelection(i);
                return;
            }
        }
    }
}

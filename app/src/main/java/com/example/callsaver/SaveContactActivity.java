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

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.chip.Chip;

import android.media.MediaPlayer;
import android.widget.SeekBar;
import android.widget.ProgressBar;
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
    
    private TextView tvToggleApiKey;
    private View llApiKeyContainer;
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
        etOpenAiApiKey = findViewById(R.id.et_openai_api_key);
        btnSaveApiKey = findViewById(R.id.btn_save_api_key);

        // Scan for recording file
        recordingFile = CallRecordingScanner.findLatestCallRecording(this, phoneNumber, callTimestamp);
        if (recordingFile != null) {
            llRecordingPanel.setVisibility(View.VISIBLE);
            setupAudioPlayer();
        }

        // Setup API Key preferences and UI controls
        SharedPreferences apiPrefs = getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);
        String savedKey = apiPrefs.getString("openai_api_key", "");
        etOpenAiApiKey.setText(savedKey);

        tvToggleApiKey.setOnClickListener(v -> {
            if (llApiKeyContainer.getVisibility() == View.VISIBLE) {
                llApiKeyContainer.setVisibility(View.GONE);
                tvToggleApiKey.setText("▼ Settings: OpenAI API Key");
            } else {
                llApiKeyContainer.setVisibility(View.VISIBLE);
                tvToggleApiKey.setText("▲ Settings: OpenAI API Key");
            }
        });

        btnSaveApiKey.setOnClickListener(v -> {
            String key = etOpenAiApiKey.getText().toString().trim();
            apiPrefs.edit().putString("openai_api_key", key).apply();
            Toast.makeText(this, "API Key saved successfully!", Toast.LENGTH_SHORT).show();
            llApiKeyContainer.setVisibility(View.GONE);
            tvToggleApiKey.setText("▼ Settings: OpenAI API Key");
        });

        // Set up auto-transcribe action
        btnAutoTranscribe.setOnClickListener(v -> {
            pbTranscribe.setVisibility(View.VISIBLE);
            btnAutoTranscribe.setEnabled(false);
            Transcriber.transcribeCallRecording(this, recordingFile, new Transcriber.TranscriptionCallback() {
                @Override
                public void onSuccess(String text) {
                    pbTranscribe.setVisibility(View.GONE);
                    btnAutoTranscribe.setEnabled(true);
                    if (text != null && !text.isEmpty()) {
                        String currentNotes = etNotes.getText().toString().trim();
                        if (!currentNotes.isEmpty()) {
                            etNotes.setText(currentNotes + "\n" + text);
                        } else {
                            etNotes.setText(text);
                        }
                        Toast.makeText(SaveContactActivity.this, "Call transcribed successfully!", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(String error) {
                    pbTranscribe.setVisibility(View.GONE);
                    btnAutoTranscribe.setEnabled(true);
                    Toast.makeText(SaveContactActivity.this, error, Toast.LENGTH_LONG).show();
                }
            });
        });

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
                Toast.makeText(SaveContactActivity.this, "Linked to existing company " + existing.getCompanyName(), Toast.LENGTH_LONG).show();
                finish();
            } else {
                // Save new local job calls database
                JobCall call = new JobCall(phoneNumber, company, round, tags, notes, callDuration, callTimestamp);
                call.setRecruiterName(recruiter);
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
}

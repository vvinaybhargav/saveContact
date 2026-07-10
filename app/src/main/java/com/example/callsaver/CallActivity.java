package com.example.callsaver;

import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;

import com.google.android.material.textfield.TextInputLayout;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen call UI shown for every call while this app is the default phone app.
 * Switches between the incoming layout (Answer / Decline), the ongoing layout
 * (Mute / Speaker / End), and a post-call note panel for answered tracked calls.
 * Enriches the caller with company / stage / tags from the local tracker DB, or the
 * saved contact name.
 */
public class CallActivity extends AppCompatActivity implements OngoingCall.Listener {

    private static final int[] AVATAR_COLORS = {
            0xFF6366F1, 0xFF10B981, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFFF59E0B, 0xFF14B8A6
    };

    private static final int REQ_CODE_SPEECH_INPUT = 1001;
    private EditText activeDialogNotesField;

    private TextView tvName, tvNumber, tvStatus, tvDuration, tvTrackInfo, tvMute, tvSpeaker, tvAvatarLetter, tvLatestNote;
    private View layoutIncoming, layoutOngoing, layoutPostCall, layoutDialpad;
    private View btnAnswer, btnDecline, btnHangup, btnMute, btnSpeaker, btnNoteSave, btnNoteSkip, btnInCallNote;
    private View btnKeypad, btnDialpadHide, btnRecord;
    private TextView tvRecord;
    private ImageView ivRecordIcon;
    private ImageView ivAvatarIcon;
    private MaterialCardView cardAvatar;
    private EditText etNote, etCompany, etName;
    private Spinner spinnerStage;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private PowerManager.WakeLock proximityLock;
    private boolean muted = false;
    private boolean speaker = false;
    private boolean wasConnected = false;
    private boolean showingPostCall = false;
    private boolean isKnownContact = false;
    private String callNumber = "";
    private JobCall trackedCall;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showWhenLockedAndTurnScreenOn();
        setContentView(R.layout.activity_call);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            try {
                PowerManager.WakeLock screenOnLock = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                        "callsaver:wake_screen_incoming"
                );
                screenOnLock.acquire(5000);
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityLock = pm.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "callsaver:proximity");
            }
        }

        tvName = findViewById(R.id.tv_call_name);
        tvNumber = findViewById(R.id.tv_call_number);
        tvStatus = findViewById(R.id.tv_call_status);
        tvDuration = findViewById(R.id.tv_call_duration);
        tvTrackInfo = findViewById(R.id.tv_call_track_info);
        tvMute = findViewById(R.id.tv_mute);
        tvSpeaker = findViewById(R.id.tv_speaker);
        tvAvatarLetter = findViewById(R.id.tv_avatar_letter);
        tvLatestNote = findViewById(R.id.tv_call_latest_note);
        ivAvatarIcon = findViewById(R.id.iv_avatar_icon);
        cardAvatar = findViewById(R.id.card_avatar);
        layoutIncoming = findViewById(R.id.layout_incoming_actions);
        layoutOngoing = findViewById(R.id.layout_ongoing_actions);
        layoutPostCall = findViewById(R.id.layout_postcall);
        layoutDialpad = findViewById(R.id.layout_dialpad);
        btnKeypad = findViewById(R.id.btn_keypad);
        btnDialpadHide = findViewById(R.id.btn_dialpad_hide);
        etNote = findViewById(R.id.et_postcall_note);
        etCompany = findViewById(R.id.et_postcall_company);
        etName = findViewById(R.id.et_postcall_name);
        spinnerStage = findViewById(R.id.spinner_postcall_stage);
        btnAnswer = findViewById(R.id.btn_answer);
        btnDecline = findViewById(R.id.btn_decline);
        btnHangup = findViewById(R.id.btn_hangup);
        btnMute = findViewById(R.id.btn_mute);
        btnSpeaker = findViewById(R.id.btn_speaker);
        btnInCallNote = findViewById(R.id.btn_incall_note);
        btnRecord = findViewById(R.id.btn_incall_record);
        ivRecordIcon = findViewById(R.id.iv_record_icon);
        tvRecord = findViewById(R.id.tv_record);
        btnNoteSave = findViewById(R.id.btn_note_save);
        btnNoteSkip = findViewById(R.id.btn_note_skip);

        bindCallerInfo(OngoingCall.getNumber());

        btnAnswer.setOnClickListener(v -> OngoingCall.answer());
        btnDecline.setOnClickListener(v -> {
            OngoingCall.reject();
            finish();
        });
        btnHangup.setOnClickListener(v -> OngoingCall.hangup());
        btnMute.setOnClickListener(v -> {
            muted = !muted;
            CallService.applyMute(muted);
            tvMute.setText(muted ? "Unmute" : "Mute");
        });
        btnSpeaker.setOnClickListener(v -> {
            speaker = !speaker;
            CallService.applySpeaker(speaker);
            tvSpeaker.setText(speaker ? "Speaker on" : "Speaker");
        });
        btnNoteSave.setOnClickListener(v -> onPostCallSave());
        btnNoteSkip.setOnClickListener(v -> finish());
        btnInCallNote.setOnClickListener(v -> showInCallNoteDialog());
        btnRecord.setOnClickListener(v -> toggleRecording());
        btnKeypad.setOnClickListener(v -> toggleDialpad(true));
        btnDialpadHide.setOnClickListener(v -> toggleDialpad(false));
        setupDtmfKeys();
        updateRecordButton(CallRecorderService.isRecording());

        // Answer immediately if launched from the notification's Answer action.
        if ("answer".equals(getIntent().getStringExtra("action"))) {
            OngoingCall.answer();
        }

        updateUi(OngoingCall.getState());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && "answer".equals(intent.getStringExtra("action"))) {
            OngoingCall.answer();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        OngoingCall.setListener(this);
        if (!OngoingCall.hasCall() && !showingPostCall) {
            finish();
            return;
        }
        if (!showingPostCall) {
            updateUi(OngoingCall.getState());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        OngoingCall.setListener(null);
        handler.removeCallbacksAndMessages(null);
        releaseProximity();
    }

    /** Screen off when the phone is at the ear during a call, on when moved away. */
    private void acquireProximity() {
        if (proximityLock != null && !proximityLock.isHeld()) {
            try {
                proximityLock.acquire(60 * 60 * 1000L);
            } catch (Exception ignored) {
            }
        }
    }

    private void releaseProximity() {
        if (proximityLock != null && proximityLock.isHeld()) {
            try {
                proximityLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onStateChanged(int state) {
        runOnUiThread(() -> updateUi(state));
    }

    private void bindCallerInfo(String number) {
        callNumber = number == null ? "" : number;
        tvNumber.setText(number == null || number.isEmpty() ? "Unknown number" : number);

        String trackerName = null;
        try {
            DatabaseHelper db = new DatabaseHelper(this);
            trackedCall = db.getJobCallByNumber(this, number);
            if (trackedCall != null) {
                String comp = trackedCall.getCompanyName();
                String rec = trackedCall.getRecruiterName();
                if (comp != null && !comp.trim().isEmpty() && rec != null && !rec.trim().isEmpty()) {
                    trackerName = rec.trim() + " @ " + comp.trim();
                } else if (comp != null && !comp.trim().isEmpty()) {
                    trackerName = comp.trim();
                } else if (rec != null && !rec.trim().isEmpty()) {
                    trackerName = rec.trim();
                }
                StringBuilder sb = new StringBuilder();
                if (trackedCall.getRoundStatus() != null && !trackedCall.getRoundStatus().trim().isEmpty()) {
                    sb.append(trackedCall.getRoundStatus());
                }
                if (trackedCall.getTags() != null && !trackedCall.getTags().trim().isEmpty()) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(trackedCall.getTags());
                }
                if (sb.length() > 0) {
                    tvTrackInfo.setText(sb.toString());
                    tvTrackInfo.setVisibility(View.VISIBLE);
                }
            }
        } catch (Exception ignored) {
        }

        // Prefer tracker company; else the phone's saved contact name; else the number.
        String display = trackerName;
        boolean named = display != null && !display.trim().isEmpty();
        if (!named) {
            String contactName = lookupContactName(number);
            if (contactName != null && !contactName.trim().isEmpty()) {
                display = contactName;
                named = true;
                isKnownContact = true;
            }
        } else {
            isKnownContact = true;
        }
        if (display == null || display.trim().isEmpty()) {
            display = (number == null || number.isEmpty()) ? "Unknown" : number;
        }
        tvName.setText(display);
        applyAvatar(display, named);

        // Show the latest note (like the tag) and enable the in-call note button.
        btnInCallNote.setVisibility(View.VISIBLE);
        boolean tracked = trackedCall != null && trackedCall.getId() > 0;
        if (tracked) {
            showLatestNote(trackedCall.getId());
        }
    }

    private void showLatestNote(long jobId) {
        try {
            DatabaseHelper db = new DatabaseHelper(this);
            List<CallNote> notes = db.getNotesForJob(jobId);

            // Take the 5 most recent notes
            List<CallNote> recentNotes = new ArrayList<>();
            for (int i = 0; i < Math.min(5, notes.size()); i++) {
                recentNotes.add(notes.get(i));
            }
            
            // Sort ascending so oldest of these 5 is shown first, newest last
            Collections.sort(recentNotes, (a, b) -> Long.compare(a.timestamp, b.timestamp));

            if (!recentNotes.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                int idx = 1;
                for (CallNote n : recentNotes) {
                    if (n.note != null && !n.note.trim().isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(idx).append(". ").append(n.note.trim());
                        idx++;
                    }
                }
                tvLatestNote.setText(sb.toString());
                tvLatestNote.setVisibility(View.VISIBLE);
                tvLatestNote.setMovementMethod(new android.text.method.ScrollingMovementMethod());
            } else {
                tvLatestNote.setVisibility(View.GONE);
            }
        } catch (Exception ignored) {
        }
    }

    private void toggleDialpad(boolean show) {
        layoutDialpad.setVisibility(show ? View.VISIBLE : View.GONE);
        layoutOngoing.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void setupDtmfKeys() {
        int[] ids = {
                R.id.dtmf_1, R.id.dtmf_2, R.id.dtmf_3, R.id.dtmf_4, R.id.dtmf_5,
                R.id.dtmf_6, R.id.dtmf_7, R.id.dtmf_8, R.id.dtmf_9,
                R.id.dtmf_star, R.id.dtmf_0, R.id.dtmf_hash
        };
        char[] chars = {'1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#'};
        for (int i = 0; i < ids.length; i++) {
            final char c = chars[i];
            View key = findViewById(ids[i]);
            if (key != null) {
                key.setOnClickListener(v -> sendDtmf(c));
            }
        }
    }

    private void sendDtmf(char c) {
        OngoingCall.playDtmf(c);
        handler.postDelayed(OngoingCall::stopDtmf, 160);
    }

    private void toggleRecording() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, 900);
            Toast.makeText(this, "Grant mic permission, then tap Record again", Toast.LENGTH_LONG).show();
            return;
        }
        if (CallRecorderService.isRecording()) {
            startService(new Intent(this, CallRecorderService.class).setAction(CallRecorderService.ACTION_STOP));
            updateRecordButton(false);
            Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show();
        } else {
            // Turn on speakerphone: on locked devices the mic is often only reachable
            // via the loudspeaker route, which also lets it pick up the other side.
            if (!speaker) {
                speaker = true;
                CallService.applySpeaker(true);
                if (tvSpeaker != null) tvSpeaker.setText("Speaker on");
            }
            Intent i = new Intent(this, CallRecorderService.class).setAction(CallRecorderService.ACTION_START);
            i.putExtra(CallRecorderService.EXTRA_NAME, tvName.getText().toString());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            updateRecordButton(true);
            Toast.makeText(this, "Recording on speaker… speak up", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateRecordButton(boolean recording) {
        if (tvRecord != null) {
            tvRecord.setText(recording ? "Stop" : "Record");
        }
        if (ivRecordIcon != null) {
            ivRecordIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    recording ? 0xFFF43F5E : 0xFFFFFFFF));
        }
    }

    private void stopRecordingIfActive() {
        if (CallRecorderService.isRecording()) {
            try {
                startService(new Intent(this, CallRecorderService.class).setAction(CallRecorderService.ACTION_STOP));
            } catch (Exception ignored) {
            }
        }
    }

    private void showInCallNoteDialog() {
        if (trackedCall == null || trackedCall.getId() <= 0) {
            try {
                DatabaseHelper db = new DatabaseHelper(this);
                String displayNum = (callNumber == null || callNumber.isEmpty()) ? "Unknown" : callNumber;
                String placeholderCompany = ""; // No name placeholder when creating log automatically
                JobCall placeholderCall = new JobCall(displayNum, placeholderCompany, "Screening", "", "", 0, System.currentTimeMillis());
                long newId = db.insertJobCall(placeholderCall);
                if (newId != -1) {
                    trackedCall = db.getJobCallByNumber(this, displayNum);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (trackedCall == null || trackedCall.getId() <= 0) {
            Toast.makeText(this, "Cannot create note: Database error", Toast.LENGTH_SHORT).show();
            return;
        }
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_incall_note, null);
        final EditText etNotes = dialogView.findViewById(R.id.et_notes);
        activeDialogNotesField = etNotes;

        TextInputLayout tilNotes = dialogView.findViewById(R.id.til_notes);
        if (tilNotes != null) {
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
        }

        final Spinner spinnerRound = dialogView.findViewById(R.id.spinner_round);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.round_statuses, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRound.setAdapter(adapter);
        int pos = adapter.getPosition(trackedCall.getRoundStatus());
        spinnerRound.setSelection(pos >= 0 ? pos : 0);

        new AlertDialog.Builder(this)
                .setTitle("Add note")
                .setView(dialogView)
                .setPositiveButton("Save", (d, w) -> {
                    DatabaseHelper db = new DatabaseHelper(this);
                    String stage = spinnerRound.getSelectedItem() != null
                            ? spinnerRound.getSelectedItem().toString() : trackedCall.getRoundStatus();
                    if (stage != null && !stage.equals(trackedCall.getRoundStatus())) {
                        trackedCall.setRoundStatus(stage);
                        db.updateJobCall(trackedCall);
                    }
                    String t = etNotes.getText().toString().trim();
                    if (!t.isEmpty()) {
                        db.insertNote(trackedCall.getId(), t, System.currentTimeMillis());
                    }
                    bindCallerInfo(callNumber); // refresh the on-screen stage/note badge
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_SPEECH_INPUT && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty() && activeDialogNotesField != null) {
                String spokenText = result.get(0);
                String currentText = activeDialogNotesField.getText().toString();
                if (currentText.trim().isEmpty()) {
                    activeDialogNotesField.setText(spokenText);
                } else {
                    activeDialogNotesField.setText(currentText + " " + spokenText);
                }
                activeDialogNotesField.setSelection(activeDialogNotesField.getText().length());
            }
        }
    }

    private void applyAvatar(String name, boolean named) {
        if (named && name != null && !name.trim().isEmpty()) {
            char first = name.trim().charAt(0);
            tvAvatarLetter.setText(String.valueOf(Character.toUpperCase(first)));
            tvAvatarLetter.setVisibility(View.VISIBLE);
            ivAvatarIcon.setVisibility(View.GONE);
            int color = AVATAR_COLORS[Math.abs(name.hashCode()) % AVATAR_COLORS.length];
            cardAvatar.setCardBackgroundColor(color);
        } else {
            tvAvatarLetter.setVisibility(View.GONE);
            ivAvatarIcon.setVisibility(View.VISIBLE);
        }
    }

    private String lookupContactName(String number) {
        if (number == null || number.isEmpty()) {
            return null;
        }
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            String[] projection = {ContactsContract.PhoneLookup.DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    return c.getString(0);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String getSimLabel() {
        try {
            android.telecom.PhoneAccountHandle handle = OngoingCall.getAccountHandle();
            if (handle != null) {
                android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    List<android.telecom.PhoneAccountHandle> handles = tm.getCallCapablePhoneAccounts();
                    if (handles != null && handles.size() > 1) {
                        for (int i = 0; i < handles.size(); i++) {
                            if (handles.get(i).getId().equals(handle.getId())) {
                                return "SIM " + (i + 1);
                            }
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
        return null;
    }

    private void updateUi(int state) {
        if (showingPostCall) {
            return;
        }
        String simLabel = getSimLabel();
        String simSuffix = (simLabel != null) ? " (" + simLabel + ")" : "";

        switch (state) {
            case Call.STATE_RINGING:
                tvStatus.setText("Incoming call" + simSuffix);
                layoutIncoming.setVisibility(View.VISIBLE);
                layoutOngoing.setVisibility(View.GONE);
                tvDuration.setVisibility(View.GONE);
                releaseProximity(); // keep the screen on while it's ringing
                break;
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
                tvStatus.setText("Calling…" + simSuffix);
                layoutIncoming.setVisibility(View.GONE);
                layoutOngoing.setVisibility(View.VISIBLE);
                tvDuration.setVisibility(View.GONE);
                acquireProximity();
                break;
            case Call.STATE_ACTIVE:
                wasConnected = true;
                tvStatus.setText("Ongoing" + simSuffix);
                layoutIncoming.setVisibility(View.GONE);
                layoutOngoing.setVisibility(View.VISIBLE);
                tvDuration.setVisibility(View.VISIBLE);
                startTimer();
                acquireProximity();
                break;
            case Call.STATE_HOLDING:
                tvStatus.setText("On hold" + simSuffix);
                break;
            case Call.STATE_DISCONNECTING:
            case Call.STATE_DISCONNECTED:
                handler.removeCallbacksAndMessages(null);
                releaseProximity();
                stopRecordingIfActive();
                // After a real conversation, offer the post-call panel for a tracked
                // recruiter (note + stage) or an unknown caller (save + note). Ordinary
                // saved contacts just close.
                boolean tracked = trackedCall != null && trackedCall.getId() > 0;
                if (wasConnected && (tracked || !isKnownContact)) {
                    showPostCall(tracked);
                } else {
                    finish();
                }
                break;
            default:
                break;
        }
    }

    private void showPostCall(boolean tracked) {
        showingPostCall = true;
        tvStatus.setText("Call ended");
        tvDuration.setVisibility(View.GONE);
        layoutIncoming.setVisibility(View.GONE);
        layoutOngoing.setVisibility(View.GONE);
        layoutDialpad.setVisibility(View.GONE);
        layoutPostCall.setVisibility(View.VISIBLE);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.round_statuses, R.layout.item_spinner_white);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerStage.setAdapter(adapter);

        if (tracked) {
            etCompany.setVisibility(View.GONE);
            etName.setVisibility(View.GONE);
            int pos = adapter.getPosition(trackedCall.getRoundStatus());
            spinnerStage.setSelection(pos >= 0 ? pos : 0);
            ((TextView) btnNoteSave).setText("Save");
        } else {
            etCompany.setVisibility(View.VISIBLE);
            etName.setVisibility(View.VISIBLE);
        }
        etNote.requestFocus();
    }

    private void onPostCallSave() {
        String note = etNote.getText().toString().trim();
        String stage = spinnerStage.getSelectedItem() != null
                ? spinnerStage.getSelectedItem().toString() : "Screening";
        DatabaseHelper db = new DatabaseHelper(this);

        try {
            if (trackedCall != null && trackedCall.getId() > 0) {
                // Existing recruiter: update stage if changed, add the note.
                if (!stage.equals(trackedCall.getRoundStatus())) {
                    trackedCall.setRoundStatus(stage);
                    db.updateJobCall(trackedCall);
                }
                if (!note.isEmpty()) {
                    db.insertNote(trackedCall.getId(), note, System.currentTimeMillis());
                }
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            } else {
                // Unknown caller: create or merge tracker entry
                String company = etCompany.getText().toString().trim();
                String recruiter = etName.getText().toString().trim();

                JobCall existingCall = null;
                if (!company.isEmpty()) {
                    existingCall = db.getJobCallByCompany(company);
                }

                if (existingCall != null) {
                    // Link new phone number to existing company
                    db.linkPhoneToJob(existingCall.getId(), callNumber, recruiter);
                    if (!note.isEmpty()) {
                        db.insertNote(existingCall.getId(), note, System.currentTimeMillis());
                    }
                    Toast.makeText(this, "Linked to existing company " + existingCall.getCompanyName(), Toast.LENGTH_LONG).show();
                } else {
                    // Create new recruiter/company entry
                    JobCall newCall = new JobCall(callNumber, company, stage, "", "", 0,
                            System.currentTimeMillis());
                    newCall.setRecruiterName(recruiter);
                    long id = db.insertJobCall(newCall);
                    if (id != -1 && !note.isEmpty()) {
                        db.insertNote(id, note, System.currentTimeMillis());
                    }
                    Toast.makeText(this, "Saved to tracker", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void saveContactDirectly(String name, String phoneNumber) {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            String accName = prefs.getString("preferred_contact_account_name", null);
            String accType = prefs.getString("preferred_contact_account_type", null);

            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            int idx = ops.size();
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accType)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accName)
                    .build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, idx)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, idx)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build());
            getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startTimer() {
        if (ticker != null) {
            return;
        }
        ticker = new Runnable() {
            @Override
            public void run() {
                long connect = OngoingCall.getConnectTimeMillis();
                long base = connect > 0 ? connect : System.currentTimeMillis();
                long secs = Math.max(0, (System.currentTimeMillis() - base) / 1000);
                tvDuration.setText(String.format(Locale.getDefault(), "%02d:%02d", secs / 60, secs % 60));
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(ticker);
    }

    private void showWhenLockedAndTurnScreenOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onBackPressed() {
        if (showingPostCall) {
            finish();
        }
        // Otherwise ignore Back during a ringing/active call; use Decline/End.
    }

}

package com.example.callsaver;

import android.content.ContentProviderOperation;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    private TextView tvName, tvNumber, tvStatus, tvDuration, tvTrackInfo, tvMute, tvSpeaker, tvAvatarLetter, tvLatestNote;
    private View layoutIncoming, layoutOngoing, layoutPostCall;
    private View btnAnswer, btnDecline, btnHangup, btnMute, btnSpeaker, btnNoteSave, btnNoteSkip, btnInCallNote;
    private ImageView ivAvatarIcon;
    private MaterialCardView cardAvatar;
    private EditText etNote, etCompany;
    private Spinner spinnerStage;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
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
        etNote = findViewById(R.id.et_postcall_note);
        etCompany = findViewById(R.id.et_postcall_company);
        spinnerStage = findViewById(R.id.spinner_postcall_stage);
        btnAnswer = findViewById(R.id.btn_answer);
        btnDecline = findViewById(R.id.btn_decline);
        btnHangup = findViewById(R.id.btn_hangup);
        btnMute = findViewById(R.id.btn_mute);
        btnSpeaker = findViewById(R.id.btn_speaker);
        btnInCallNote = findViewById(R.id.btn_incall_note);
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

        updateUi(OngoingCall.getState());
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
                if (trackedCall.getCompanyName() != null && !trackedCall.getCompanyName().trim().isEmpty()) {
                    trackerName = trackedCall.getCompanyName();
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

        // Show the latest note (like the tag) and enable the in-call note button for tracked calls.
        boolean tracked = trackedCall != null && trackedCall.getId() > 0;
        btnInCallNote.setVisibility(tracked ? View.VISIBLE : View.GONE);
        if (tracked) {
            showLatestNote(trackedCall.getId());
        }
    }

    private void showLatestNote(long jobId) {
        try {
            List<CallNote> notes = new DatabaseHelper(this).getNotesForJob(jobId);
            if (!notes.isEmpty() && notes.get(0).note != null && !notes.get(0).note.trim().isEmpty()) {
                tvLatestNote.setText(notes.get(0).note);
                tvLatestNote.setVisibility(View.VISIBLE);
            } else {
                tvLatestNote.setVisibility(View.GONE);
            }
        } catch (Exception ignored) {
        }
    }

    private void showInCallNoteDialog() {
        if (trackedCall == null || trackedCall.getId() <= 0) {
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("Note…");
        input.setMinLines(2);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(this)
                .setTitle("Add note")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String t = input.getText().toString().trim();
                    if (!t.isEmpty()) {
                        new DatabaseHelper(this).insertNote(
                                trackedCall.getId(), t, System.currentTimeMillis());
                        showLatestNote(trackedCall.getId());
                        Toast.makeText(this, "Note added", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    private void updateUi(int state) {
        if (showingPostCall) {
            return;
        }
        switch (state) {
            case Call.STATE_RINGING:
                tvStatus.setText("Incoming call");
                layoutIncoming.setVisibility(View.VISIBLE);
                layoutOngoing.setVisibility(View.GONE);
                tvDuration.setVisibility(View.GONE);
                break;
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
                tvStatus.setText("Calling…");
                layoutIncoming.setVisibility(View.GONE);
                layoutOngoing.setVisibility(View.VISIBLE);
                tvDuration.setVisibility(View.GONE);
                break;
            case Call.STATE_ACTIVE:
                wasConnected = true;
                tvStatus.setText("Ongoing");
                layoutIncoming.setVisibility(View.GONE);
                layoutOngoing.setVisibility(View.VISIBLE);
                tvDuration.setVisibility(View.VISIBLE);
                startTimer();
                break;
            case Call.STATE_HOLDING:
                tvStatus.setText("On hold");
                break;
            case Call.STATE_DISCONNECTING:
            case Call.STATE_DISCONNECTED:
                handler.removeCallbacksAndMessages(null);
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
        layoutPostCall.setVisibility(View.VISIBLE);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.round_statuses, R.layout.item_spinner_white);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerStage.setAdapter(adapter);

        if (tracked) {
            etCompany.setVisibility(View.GONE);
            int pos = adapter.getPosition(trackedCall.getRoundStatus());
            spinnerStage.setSelection(pos >= 0 ? pos : 0);
            ((TextView) btnNoteSave).setText("Save");
        } else {
            etCompany.setVisibility(View.VISIBLE);
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
                // Unknown caller: create a tracker entry + save to contacts + note.
                String company = etCompany.getText().toString().trim();
                if (company.isEmpty()) {
                    if (note.isEmpty()) {
                        finish();
                        return;
                    }
                    Toast.makeText(this, "Enter a name to save this caller", Toast.LENGTH_SHORT).show();
                    return;
                }
                JobCall newCall = new JobCall(callNumber, company, stage, "", "", 0,
                        System.currentTimeMillis());
                long id = db.insertJobCall(newCall);
                if (id != -1 && !note.isEmpty()) {
                    db.insertNote(id, note, System.currentTimeMillis());
                }
                saveContactDirectly(company, callNumber);
                Toast.makeText(this, "Saved to tracker & contacts", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void saveContactDirectly(String name, String phoneNumber) {
        try {
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            int idx = ops.size();
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
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
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
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

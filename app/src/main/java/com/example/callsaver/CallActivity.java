package com.example.callsaver;

import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.Date;
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

    private TextView tvName, tvNumber, tvStatus, tvDuration, tvTrackInfo, tvMute, tvSpeaker, tvAvatarLetter;
    private View layoutIncoming, layoutOngoing, layoutPostCall;
    private View btnAnswer, btnDecline, btnHangup, btnMute, btnSpeaker, btnNoteSave, btnNoteSkip;
    private ImageView ivAvatarIcon;
    private MaterialCardView cardAvatar;
    private EditText etNote;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private boolean muted = false;
    private boolean speaker = false;
    private boolean wasConnected = false;
    private boolean showingPostCall = false;
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
        ivAvatarIcon = findViewById(R.id.iv_avatar_icon);
        cardAvatar = findViewById(R.id.card_avatar);
        layoutIncoming = findViewById(R.id.layout_incoming_actions);
        layoutOngoing = findViewById(R.id.layout_ongoing_actions);
        layoutPostCall = findViewById(R.id.layout_postcall);
        etNote = findViewById(R.id.et_postcall_note);
        btnAnswer = findViewById(R.id.btn_answer);
        btnDecline = findViewById(R.id.btn_decline);
        btnHangup = findViewById(R.id.btn_hangup);
        btnMute = findViewById(R.id.btn_mute);
        btnSpeaker = findViewById(R.id.btn_speaker);
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
        btnNoteSave.setOnClickListener(v -> saveNoteAndFinish());
        btnNoteSkip.setOnClickListener(v -> finish());

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
            }
        }
        if (display == null || display.trim().isEmpty()) {
            display = (number == null || number.isEmpty()) ? "Unknown" : number;
        }
        tvName.setText(display);
        applyAvatar(display, named);
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
                // Offer to jot a note only after a real conversation with a tracked recruiter.
                if (wasConnected && trackedCall != null && trackedCall.getId() > 0) {
                    showPostCall();
                } else {
                    finish();
                }
                break;
            default:
                break;
        }
    }

    private void showPostCall() {
        showingPostCall = true;
        tvStatus.setText("Call ended");
        tvDuration.setVisibility(View.GONE);
        layoutIncoming.setVisibility(View.GONE);
        layoutOngoing.setVisibility(View.GONE);
        layoutPostCall.setVisibility(View.VISIBLE);
        etNote.requestFocus();
    }

    private void saveNoteAndFinish() {
        String note = etNote.getText().toString().trim();
        if (!note.isEmpty() && trackedCall != null) {
            try {
                String stamp = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(new Date());
                String existing = trackedCall.getNotes() == null ? "" : trackedCall.getNotes();
                String combined = "[" + stamp + "] " + note + (existing.isEmpty() ? "" : "\n" + existing);
                trackedCall.setNotes(combined);
                new DatabaseHelper(this).updateJobCall(trackedCall);
                Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't save note", Toast.LENGTH_SHORT).show();
            }
        }
        finish();
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

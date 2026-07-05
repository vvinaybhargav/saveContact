package com.example.callsaver;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/**
 * Full-screen call UI shown for every call while this app is the default phone app.
 * Switches between the incoming layout (Answer / Decline) and the ongoing layout
 * (Mute / Speaker / End) based on the telecom call state, and enriches the caller
 * with company / stage / tags from the local tracker DB.
 */
public class CallActivity extends AppCompatActivity implements OngoingCall.Listener {

    private TextView tvName, tvNumber, tvStatus, tvDuration, tvTrackInfo, tvMute, tvSpeaker;
    private View layoutIncoming, layoutOngoing;
    private View btnAnswer, btnDecline, btnHangup, btnMute, btnSpeaker;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private boolean muted = false;
    private boolean speaker = false;

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
        layoutIncoming = findViewById(R.id.layout_incoming_actions);
        layoutOngoing = findViewById(R.id.layout_ongoing_actions);
        btnAnswer = findViewById(R.id.btn_answer);
        btnDecline = findViewById(R.id.btn_decline);
        btnHangup = findViewById(R.id.btn_hangup);
        btnMute = findViewById(R.id.btn_mute);
        btnSpeaker = findViewById(R.id.btn_speaker);

        bindCallerInfo(OngoingCall.getNumber());

        btnAnswer.setOnClickListener(v -> OngoingCall.answer());
        btnDecline.setOnClickListener(v -> {
            OngoingCall.reject();
            finish();
        });
        btnHangup.setOnClickListener(v -> {
            OngoingCall.hangup();
            finish();
        });
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

        updateUi(OngoingCall.getState());
    }

    @Override
    protected void onResume() {
        super.onResume();
        OngoingCall.setListener(this);
        if (!OngoingCall.hasCall()) {
            finish();
            return;
        }
        updateUi(OngoingCall.getState());
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

        String display = number;
        try {
            DatabaseHelper db = new DatabaseHelper(this);
            JobCall jc = db.getJobCallByNumber(this, number);
            if (jc != null) {
                if (jc.getCompanyName() != null && !jc.getCompanyName().trim().isEmpty()) {
                    display = jc.getCompanyName();
                }
                StringBuilder sb = new StringBuilder();
                if (jc.getRoundStatus() != null && !jc.getRoundStatus().trim().isEmpty()) {
                    sb.append(jc.getRoundStatus());
                }
                if (jc.getTags() != null && !jc.getTags().trim().isEmpty()) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(jc.getTags());
                }
                if (sb.length() > 0) {
                    tvTrackInfo.setText(sb.toString());
                    tvTrackInfo.setVisibility(View.VISIBLE);
                }
            }
        } catch (Exception ignored) {
        }

        tvName.setText(display == null || display.isEmpty() ? "Unknown" : display);
    }

    private void updateUi(int state) {
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
                tvStatus.setText("Call ended");
                handler.removeCallbacksAndMessages(null);
                finish();
                break;
            default:
                break;
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
        // Prevent accidentally dropping the call UI with Back; use End/Decline instead.
    }
}

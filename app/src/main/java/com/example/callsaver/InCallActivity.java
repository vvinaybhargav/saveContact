package com.example.callsaver;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import android.app.Dialog;
import android.text.Html;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputLayout;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.net.Uri;
import java.io.File;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Calendar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen in-call UI shown while CallSaver is the default dialer, replacing both
 * the system's stock call screen and the old small floating CallerIdService banner.
 * Shows caller/company info, notes/call-history/skills-match, and real call controls
 * (answer/decline/mute/speaker/end) wired to the live android.telecom.Call via
 * CallSaverInCallService.
 */
public class InCallActivity extends AppCompatActivity {

    private static InCallActivity instance;

    private String phoneNumber;
    private String company;
    private String roundStatus;
    private String tags;
    private long jobCallId;
    private String recruiter;
    private String contactName;
    private String simLabel;
    // True when this number is a plain saved phone-contact with no recruiter/job-lead
    // data of its own - no point showing the notes/skills tracker UI for a friend or
    // family member, so we hide it and just show name + number.
    private boolean isPersonalContact;
    // When true, this screen is opened outside a live call (from the post-call
    // notification, or later from Tracker/Upcoming) purely to review/log details -
    // so it hides the call controls and opens the capture form directly, and must
    // NOT auto-finish on call-state changes.
    private boolean reviewMode;

    private TextView tvCallTimer;
    private android.widget.LinearLayout llIncomingControls;
    private android.widget.LinearLayout llActiveControls;
    private ImageView btnToggleMute;
    private TextView tvMuteLabel;
    private ImageView btnToggleSpeaker;
    private TextView tvSpeakerLabel;
    private ImageView btnToggleNote;
    private View llOverlayEditPanel;
    private android.os.PowerManager.WakeLock proximityWakeLock;

    // Up to 3 JD screenshots (comma-joined into JobCall.jdImagePath for storage).
    private static final int REQ_CODE_PICK_JD_SCREENSHOT = 9001;
    private final String[] jdPaths = new String[3];

    // Note-editor fields, promoted to instance state so notes can auto-save (debounced
    // while typing, and immediately when the call ends / screen is destroyed) instead
    // of requiring an explicit Save tap that's easy to miss before a call disconnects.
    private EditText etOverlayNoteInput;
    private EditText etOverlayPhone;
    private EditText etOverlayName;
    private EditText etOverlayCompany;
    private EditText etOverlayExpectedCtc;
    private EditText etOverlayNextCall;
    private Spinner spinnerOverlayRound;
    private TextView tvNotesTimelineField;
    private TextView tvCallerStatusField;
    private int prefillRoundPosition = -1;
    private String lastAutoSavedNoteText = "";
    // Chip-picked quick facts (role/work-mode/status etc.) - stored as tags, not appended
    // into the free-text note, and shown as their own boxes next to the call status.
    private String tagsValue = "";
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSaveRunnable = () -> persistNoteAndDetails(false);

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private long callStartElapsedMs = -1;
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (callStartElapsedMs < 0) return;
            long secs = (System.currentTimeMillis() - callStartElapsedMs) / 1000;
            long m = secs / 60;
            long s = secs % 60;
            if (tvCallTimer != null) {
                tvCallTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", m, s));
            }
            timerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Blend the system bars into the premium gradient for a full-bleed look.
        try {
            getWindow().setStatusBarColor(androidx.core.content.ContextCompat.getColor(this, R.color.incall_grad_top));
            getWindow().setNavigationBarColor(androidx.core.content.ContextCompat.getColor(this, R.color.incall_grad_bottom));
        } catch (Exception ignored) {
        }

        setContentView(R.layout.activity_in_call);
        instance = this;

        Intent intent = getIntent();
        phoneNumber = intent.getStringExtra("phone_number");
        company = intent.getStringExtra("company_name");
        roundStatus = intent.getStringExtra("round_status");
        tags = intent.getStringExtra("tags");
        jobCallId = intent.getLongExtra("job_call_id", -1);
        recruiter = intent.getStringExtra("recruiter_name");
        contactName = intent.getStringExtra("contact_name");
        simLabel = intent.getStringExtra("sim_label");
        reviewMode = "review".equals(intent.getStringExtra("mode"));
        int initialState = intent.getIntExtra("initial_state", Call.STATE_ACTIVE);

        bindCallerInfo();
        // bindControls() must run before bindNotesAndSkills(): it's what assigns
        // llOverlayEditPanel, and bindNotesAndSkills() -> bindNoteEditor() wires the
        // Cancel/Save buttons only if llOverlayEditPanel is already non-null - with the
        // old order that check always failed, so those two buttons silently did nothing.
        bindControls();
        bindNotesAndSkills();

        if (reviewMode) {
            enterReviewMode();
        } else {
            applyState(initialState);
        }
    }

    /** Configures the screen for out-of-call review/logging: no call controls, form open. */
    private void enterReviewMode() {
        stopTimer();
        if (llIncomingControls != null) llIncomingControls.setVisibility(View.GONE);
        if (llActiveControls != null) llActiveControls.setVisibility(View.GONE);
        if (tvCallTimer != null) tvCallTimer.setText("Log call details");
        if (llOverlayEditPanel != null) llOverlayEditPanel.setVisibility(View.VISIBLE);
        View btnClose = findViewById(R.id.btn_overlay_close);
        if (btnClose != null) {
            btnClose.setVisibility(View.VISIBLE);
            // Always works, no matter what - a guaranteed way out of this screen.
            btnClose.setOnClickListener(v -> {
                try {
                    persistNoteAndDetails(false);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                finish();
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onDestroy() {
        // The call can end (and this screen auto-close via finishIfOpen) at any moment -
        // flush whatever's in the note editor right now instead of losing it because the
        // user hadn't tapped Save yet.
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        if (!isPersonalContact) persistNoteAndDetails(false);
        releaseProximityLock();
        super.onDestroy();
        if (instance == this) instance = null;
        timerHandler.removeCallbacks(timerRunnable);
    }

    @Override
    public void onBackPressed() {
        if (!reviewMode && CallSaverInCallService.getActiveCall() != null) {
            moveTaskToBack(true);
            Toast.makeText(this, "Call running in background. Tap notification to return.", Toast.LENGTH_SHORT).show();
        } else if (reviewMode) {
            finish();
        } else {
            super.onBackPressed();
        }
    }

    /** Called by CallSaverInCallService whenever the live call's state changes. */
    public static void notifyCallStateChanged(int state) {
        if (instance != null && !instance.reviewMode) {
            instance.runOnUiThread(() -> instance.applyState(state));
        }
    }

    /** Called by CallSaverInCallService once Telecom confirms the real mute/speaker
     *  state - the click handlers request a change, this is what actually reflects it. */
    public static void notifyAudioStateChanged() {
        if (instance != null) {
            instance.runOnUiThread(instance::updateMuteSpeakerUi);
        }
    }

    public static void finishIfOpen() {
        // Only close a live-call screen; a review/logging screen isn't tied to a call.
        if (instance != null && !instance.reviewMode) {
            instance.runOnUiThread(() -> {
                if (!instance.isFinishing()) instance.finish();
            });
        }
    }

    private void applyState(int state) {
        boolean ringing = state == Call.STATE_RINGING;
        boolean active = state == Call.STATE_ACTIVE || state == Call.STATE_DIALING || state == Call.STATE_CONNECTING;

        llIncomingControls.setVisibility(ringing ? View.VISIBLE : View.GONE);
        llActiveControls.setVisibility(active ? View.VISIBLE : View.GONE);

        if (state == Call.STATE_RINGING) {
            tvCallTimer.setText("Incoming call…");
            stopTimer();
        } else if (state == Call.STATE_DIALING || state == Call.STATE_CONNECTING) {
            tvCallTimer.setText("Calling…");
            stopTimer();
        } else if (state == Call.STATE_ACTIVE) {
            startTimerIfNeeded();
            acquireProximityLock();
        } else if (state == Call.STATE_DISCONNECTED) {
            stopTimer();
            releaseProximityLock();
        }
        updateMuteSpeakerUi();
    }

    /**
     * FLAG_KEEP_SCREEN_ON (needed so the call screen doesn't dim/lock mid-call) also
     * stops the normal "screen off near ear" behavior, so we drive it ourselves via the
     * proximity wake lock while the call is actually connected/active.
     */
    private void acquireProximityLock() {
        if (reviewMode || proximityWakeLock != null) return;
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = pm.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "CallSaver:ProximityLock");
            proximityWakeLock.setReferenceCounted(false);
            proximityWakeLock.acquire();
        }
    }

    private void releaseProximityLock() {
        if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
            proximityWakeLock.release();
        }
        proximityWakeLock = null;
    }

    private void startTimerIfNeeded() {
        if (callStartElapsedMs < 0) {
            callStartElapsedMs = System.currentTimeMillis();
            timerHandler.post(timerRunnable);
        }
    }

    private void stopTimer() {
        callStartElapsedMs = -1;
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void bindControls() {
        tvCallTimer = findViewById(R.id.tv_call_timer);
        llIncomingControls = findViewById(R.id.ll_incoming_controls);
        llActiveControls = findViewById(R.id.ll_active_controls);
        btnToggleMute = findViewById(R.id.btn_toggle_mute);
        tvMuteLabel = findViewById(R.id.tv_mute_label);
        btnToggleSpeaker = findViewById(R.id.btn_toggle_speaker);
        tvSpeakerLabel = findViewById(R.id.tv_speaker_label);
        btnToggleNote = findViewById(R.id.btn_toggle_note);
        llOverlayEditPanel = findViewById(R.id.ll_overlay_edit_panel);
        bindDialpad();

        FloatingActionButton btnAnswer = findViewById(R.id.btn_answer_call);
        FloatingActionButton btnDecline = findViewById(R.id.btn_decline_call);
        FloatingActionButton btnEnd = findViewById(R.id.btn_end_call);

        if (btnAnswer != null) {
            btnAnswer.setOnClickListener(v -> CallSaverInCallService.answer());
        }
        if (btnDecline != null) {
            btnDecline.setOnClickListener(v -> {
                CallSaverInCallService.reject();
                finish();
            });
        }
        if (btnEnd != null) {
            btnEnd.setOnClickListener(v -> {
                CallSaverInCallService.hangUp();
                finish();
            });
        }
        if (btnToggleMute != null) {
            btnToggleMute.setOnClickListener(v -> {
                CallSaverInCallService.setCallMuted(!CallSaverInCallService.isMuted());
                updateMuteSpeakerUi();
            });
        }
        if (btnToggleSpeaker != null) {
            btnToggleSpeaker.setOnClickListener(v -> {
                CallSaverInCallService.setSpeakerOn(!CallSaverInCallService.isSpeakerOn());
                updateMuteSpeakerUi();
            });
        }
        if (btnToggleNote != null && llOverlayEditPanel != null) {
            btnToggleNote.setOnClickListener(v -> {
                boolean visible = llOverlayEditPanel.getVisibility() == View.VISIBLE;
                llOverlayEditPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
            });
        }
        if (isPersonalContact) {
            View toggleNoteColumn = findViewById(R.id.ll_toggle_note_column);
            if (toggleNoteColumn != null) toggleNoteColumn.setVisibility(View.GONE);
        }
    }

    /** In-call DTMF keypad, for entering extensions/IVR digits mid-call. */
    private void bindDialpad() {
        View btnToggleDialpad = findViewById(R.id.btn_toggle_dialpad);
        View llDialpad = findViewById(R.id.ll_overlay_dialpad);
        TextView tvDigits = findViewById(R.id.tv_overlay_dialpad_digits);
        android.widget.LinearLayout grid = findViewById(R.id.ll_overlay_dialpad_grid);
        View btnClose = findViewById(R.id.tv_overlay_dialpad_close);
        if (llDialpad == null || grid == null) return;

        float density = getResources().getDisplayMetrics().density;
        int keySize = Math.round(58 * density);
        int keyMargin = Math.round(10 * density);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"*", "0", "#"}};
        for (String[] rowKeys : rows) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER);
            for (String key : rowKeys) {
                TextView keyTv = new TextView(this);
                keyTv.setText(key);
                keyTv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.white_constant));
                keyTv.setTextSize(22);
                keyTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                keyTv.setGravity(android.view.Gravity.CENTER);
                keyTv.setBackgroundResource(R.drawable.bg_glass_circle);
                keyTv.setClickable(true);
                keyTv.setFocusable(true);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(keySize, keySize);
                lp.setMargins(keyMargin, keyMargin, keyMargin, keyMargin);
                keyTv.setLayoutParams(lp);
                keyTv.setOnClickListener(v -> {
                    char digit = key.charAt(0);
                    CallSaverInCallService.playDtmfTone(digit);
                    CallSaverInCallService.stopDtmfTone();
                    if (tvDigits != null) tvDigits.append(key);
                });
                row.addView(keyTv);
            }
            grid.addView(row);
        }

        if (btnToggleDialpad != null) {
            btnToggleDialpad.setOnClickListener(v -> {
                boolean visible = llDialpad.getVisibility() == View.VISIBLE;
                llDialpad.setVisibility(visible ? View.GONE : View.VISIBLE);
                if (!visible && tvDigits != null) tvDigits.setText("");
            });
        }
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> llDialpad.setVisibility(View.GONE));
        }
    }

    private void updateMuteSpeakerUi() {
        boolean muted = CallSaverInCallService.isMuted();
        boolean speaker = CallSaverInCallService.isSpeakerOn();
        if (tvMuteLabel != null) tvMuteLabel.setText(muted ? "Muted" : "Mute");
        if (tvSpeakerLabel != null) tvSpeakerLabel.setText(speaker ? "Speaker On" : "Speaker");
        // ON = solid white circle with a dark icon (like the stock phone app), so it's
        // obvious at a glance instead of just a subtle tint change.
        int darkIcon = android.graphics.Color.parseColor("#1C1C1E");
        int whiteIcon = android.graphics.Color.parseColor("#FFFFFF");
        if (btnToggleMute != null) {
            btnToggleMute.setBackgroundResource(muted ? R.drawable.bg_glass_circle_active : R.drawable.bg_glass_circle);
            btnToggleMute.setColorFilter(muted ? darkIcon : whiteIcon);
            btnToggleMute.setImageResource(muted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        }
        if (btnToggleSpeaker != null) {
            btnToggleSpeaker.setBackgroundResource(speaker ? R.drawable.bg_glass_circle_active : R.drawable.bg_glass_circle);
            btnToggleSpeaker.setColorFilter(speaker ? darkIcon : whiteIcon);
            btnToggleSpeaker.setImageResource(speaker ? R.drawable.ic_volume_up : R.drawable.ic_volume_off);
        }
    }

    private void bindCallerInfo() {
        TextView tvCallerName = findViewById(R.id.tv_overlay_caller_name);
        TextView tvCallerStatus = findViewById(R.id.tv_overlay_caller_status);
        TextView tvAvatarLetter = findViewById(R.id.tv_overlay_avatar_letter);
        CardView cardAvatar = findViewById(R.id.card_overlay_avatar);

        String candidateName = "";
        String appliedRole = "";
        if (jobCallId != -1) {
            DatabaseHelper db = new DatabaseHelper(this);
            JobCall matchedCall = db.getJobCallByNumber(this, phoneNumber);
            if (matchedCall != null) {
                candidateName = matchedCall.getCandidateName();
                roundStatus = matchedCall.getRoundStatus();
                tags = matchedCall.getTags();
                company = matchedCall.getCompanyName();
                recruiter = matchedCall.getRecruiterName();
                appliedRole = matchedCall.getAppliedRole();
            }
        }

        // Nothing saved for this number yet - fall back to the device's own contacts
        // instead of showing a placeholder like "Recruiter Lead".
        if (!notEmpty(candidateName) && !notEmpty(company) && !notEmpty(recruiter) && !notEmpty(contactName)) {
            contactName = TrackerFragment.getContactNameByNumber(this, phoneNumber);
        }

        // A plain saved phone-contact with no recruiter/job-lead data of its own -
        // no notes/skills tracker for a friend or family member, just name + number.
        isPersonalContact = jobCallId == -1 && notEmpty(contactName)
                && !notEmpty(candidateName) && !notEmpty(company) && !notEmpty(recruiter);

        String title;
        if (isPersonalContact) {
            title = contactName.trim();
        } else if (notEmpty(candidateName) && notEmpty(company)) {
            title = candidateName.trim() + " @ " + company.trim();
        } else if (notEmpty(company) && notEmpty(recruiter)) {
            title = recruiter.trim() + " @ " + company.trim();
        } else if (notEmpty(company)) {
            title = company.trim();
        } else if (notEmpty(candidateName)) {
            title = candidateName.trim();
        } else if (notEmpty(recruiter)) {
            title = recruiter.trim();
        } else if (notEmpty(contactName)) {
            title = contactName.trim();
        } else {
            title = notEmpty(phoneNumber) ? phoneNumber : "Unknown";
        }
        tvCallerName.setText(title);

        // Always surface the raw number too, next to whatever status/round info we have.
        String statusText;
        if (isPersonalContact) {
            statusText = notEmpty(phoneNumber) ? phoneNumber : "";
        } else {
            statusText = notEmpty(roundStatus) ? roundStatus : "First time";
            if (notEmpty(appliedRole)) {
                statusText += " - " + appliedRole.trim() + " role";
            }
            if (notEmpty(phoneNumber)) {
                statusText += "  •  " + phoneNumber;
            }
        }
        if (notEmpty(simLabel) && !reviewMode) {
            statusText += "  •  " + simLabel.trim();
        }
        tvCallerStatus.setText(statusText);

        String initialChar = notEmpty(title) ? String.valueOf(title.charAt(0)).toUpperCase() : "?";
        tvAvatarLetter.setText(initialChar);
        int[] avatarColors = {0xFF6E6E76, 0xFF10B981, 0xFF3B82F6, 0xFF64748B, 0xFFEC4899, 0xFFF59E0B, 0xFF14B8A6};
        int colorIndex = Math.abs(title.hashCode()) % avatarColors.length;
        if (cardAvatar != null) {
            cardAvatar.setCardBackgroundColor(avatarColors[colorIndex]);
        }
    }

    private boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private void bindNotesAndSkills() {
        if (isPersonalContact) {
            View notesCard = findViewById(R.id.ll_overlay_notes_card);
            if (notesCard != null) notesCard.setVisibility(View.GONE);
            View skillsCard = findViewById(R.id.ll_overlay_skills_match_container);
            if (skillsCard != null) skillsCard.setVisibility(View.GONE);
            return;
        }

        TextView tvNotesTimeline = findViewById(R.id.tv_overlay_notes);
        android.widget.LinearLayout llCollapsedNotes = findViewById(R.id.ll_overlay_collapsed_notes);
        android.widget.LinearLayout llExpandedNotes = findViewById(R.id.ll_overlay_expanded_notes);
        android.widget.LinearLayout llExpandedList = findViewById(R.id.ll_overlay_expanded_list);
        TextView btnExpand = findViewById(R.id.tv_overlay_expand_button);
        TextView btnCollapse = findViewById(R.id.tv_overlay_collapse_button);
        View llRecentCall = findViewById(R.id.ll_overlay_recent_call_container);
        TextView tvRecentCall = findViewById(R.id.tv_overlay_recent_call);

        DatabaseHelper dbHelper = new DatabaseHelper(this);

        List<String> allCleanedPoints = new ArrayList<>();
        List<CallNote> notesList = new ArrayList<>();
        if (jobCallId != -1) {
            notesList = dbHelper.getNotesForJob(jobCallId);
            // getNotesForJob returns newest-first - build the collapsed preview in
            // chronological (oldest -> newest) order instead, so a just-added note
            // appears at the bottom/last, not jumping to the top as "most recent".
            List<CallNote> chronoForCollapsed = new ArrayList<>(notesList);
            Collections.reverse(chronoForCollapsed);
            for (CallNote note : chronoForCollapsed) {
                String cleaned = cleanNoteText(note.note);
                for (String line : cleaned.split("\n")) {
                    String lineTrimmed = line.trim();
                    if (!lineTrimmed.isEmpty()) {
                        if (!lineTrimmed.startsWith("•") && !lineTrimmed.startsWith("-")) {
                            lineTrimmed = "• " + lineTrimmed;
                        }
                        allCleanedPoints.add(lineTrimmed);
                    }
                }
            }
        }

        if (jobCallId == -1) {
            tvNotesTimeline.setText("• Not saved in Tracker yet.");
        } else {
            StringBuilder collapsedSb = new StringBuilder();
            int total = allCleanedPoints.size();
            int pointsToShow = Math.min(total, 3);
            int startIdx = total - pointsToShow;
            for (int i = startIdx; i < total; i++) {
                if (collapsedSb.length() > 0) collapsedSb.append("\n");
                collapsedSb.append(allCleanedPoints.get(i));
            }
            String collapsedStr = collapsedSb.toString();
            if (collapsedStr.isEmpty()) collapsedStr = "• No previous notes.";
            tvNotesTimeline.setText(collapsedStr);
        }

        if (llExpandedList != null && !notesList.isEmpty()) {
            llExpandedList.removeAllViews();

            List<CallNote> callNotes = new ArrayList<>();
            List<CallNote> emailNotes = new ArrayList<>();
            List<CallNote> manualNotes = new ArrayList<>();

            for (CallNote n : notesList) {
                if (DatabaseHelper.NOTE_SOURCE_EMAIL.equals(n.source) || (n.note != null && n.note.startsWith("📧"))) {
                    emailNotes.add(n);
                } else if (DatabaseHelper.NOTE_SOURCE_MANUAL.equals(n.source) || n.isManual()) {
                    manualNotes.add(n);
                } else {
                    callNotes.add(n);
                }
            }

            // Render 3 separate sections for Call, Email, and Manual notes
            renderNoteSection("📞 Call Notes", callNotes, llExpandedList);
            renderNoteSection("📧 Email Notes", emailNotes, llExpandedList);
            renderNoteSection("📝 Manual Notes", manualNotes, llExpandedList);
        }

        boolean hasRecentCallData = false;
        if (llRecentCall != null && tvRecentCall != null && jobCallId != -1) {
            long[] times = dbHelper.getFirstAndRecentCallTimes(jobCallId);
            if (times[0] > 0) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
                String firstCallText = sdf.format(new java.util.Date(times[0]));
                String recentCallText = times[1] > 0 ? sdf.format(new java.util.Date(times[1])) : "NA";
                tvRecentCall.setText("First call - " + firstCallText + "  |  Recent call - " + recentCallText);
                hasRecentCallData = true;
            }
        }
        if (llRecentCall != null) llRecentCall.setVisibility(View.GONE);
        final boolean finalHasRecentCallData = hasRecentCallData;

        if (btnExpand != null && btnCollapse != null && llCollapsedNotes != null && llExpandedNotes != null) {
            btnExpand.setOnClickListener(v -> {
                llCollapsedNotes.setVisibility(View.GONE);
                llExpandedNotes.setVisibility(View.VISIBLE);
                if (llRecentCall != null) {
                    llRecentCall.setVisibility(finalHasRecentCallData ? View.VISIBLE : View.GONE);
                }
            });
            btnCollapse.setOnClickListener(v -> {
                llCollapsedNotes.setVisibility(View.VISIBLE);
                llExpandedNotes.setVisibility(View.GONE);
                if (llRecentCall != null) llRecentCall.setVisibility(View.GONE);
            });
        }

        View llSkillsMatch = findViewById(R.id.ll_overlay_skills_match_container);
        TextView tvSkillsMatching = findViewById(R.id.tv_overlay_skills_matching);
        TextView tvSkillsNotMatching = findViewById(R.id.tv_overlay_skills_not_matching);
        if (llSkillsMatch != null && tvSkillsMatching != null && tvSkillsNotMatching != null && jobCallId != -1) {
            JobCall currentJobCall = dbHelper.getJobCallById(jobCallId);
            String matching = currentJobCall != null ? currentJobCall.getMatchingSkills() : "";
            String notMatching = currentJobCall != null ? currentJobCall.getNotMatchingSkills() : "";
            if ((matching == null || matching.isEmpty()) && (notMatching == null || notMatching.isEmpty())) {
                llSkillsMatch.setVisibility(View.GONE);
            } else {
                tvSkillsMatching.setText(matching == null || matching.isEmpty() ? "-" : matching);
                tvSkillsNotMatching.setText(notMatching == null || notMatching.isEmpty() ? "-" : notMatching);
                llSkillsMatch.setVisibility(View.VISIBLE);
            }
        }

        View cardAttachedEmails = findViewById(R.id.ll_overlay_attached_emails_card);
        android.widget.LinearLayout llEmailsList = findViewById(R.id.ll_overlay_emails_list);
        if (cardAttachedEmails != null && llEmailsList != null && jobCallId != -1) {
            List<EmailMessage> attachedEmails = dbHelper.getEmailsForJob(jobCallId);
            if (attachedEmails != null && !attachedEmails.isEmpty()) {
                cardAttachedEmails.setVisibility(View.VISIBLE);
                llEmailsList.removeAllViews();

                java.text.SimpleDateFormat emailSdf = new java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());

                for (EmailMessage email : attachedEmails) {
                    View row = LayoutInflater.from(this).inflate(R.layout.item_log_email_row, llEmailsList, false);

                    TextView tvSub = row.findViewById(R.id.tv_log_email_subject);
                    TextView tvSender = row.findViewById(R.id.tv_log_email_sender);
                    TextView tvSnippet = row.findViewById(R.id.tv_log_email_snippet);
                    TextView tvTime = row.findViewById(R.id.tv_log_email_time);

                    if (tvSub != null) tvSub.setText(email.getSubject().isEmpty() ? "(No Subject)" : email.getSubject());
                    if (tvSender != null) tvSender.setText("From: " + email.getSender());
                    if (tvSnippet != null) tvSnippet.setText(email.getSnippet());
                    if (tvTime != null) {
                        long ts = email.getReceivedTimestamp();
                        tvTime.setText(ts > 0 ? emailSdf.format(new java.util.Date(ts)) : "");
                    }

                    row.setOnClickListener(v -> showEmailDetailDialog(email));
                    llEmailsList.addView(row);
                }
            } else {
                cardAttachedEmails.setVisibility(View.GONE);
            }
        }

        View cardJd = findViewById(R.id.ll_overlay_jd_card);
        TextView tvJdText = findViewById(R.id.tv_overlay_jd_text);
        if (cardJd != null && tvJdText != null && jobCallId != -1) {
            JobCall call = dbHelper.getJobCallById(jobCallId);
            if (call != null && !call.getJdText().trim().isEmpty()) {
                cardJd.setVisibility(View.VISIBLE);
                tvJdText.setText(call.getJdText());
            } else {
                cardJd.setVisibility(View.GONE);
            }
        }

        bindNoteEditor(tvNotesTimeline, tvCallerStatusRef());

        View chipSummary = findViewById(R.id.chip_sec_summary);
        View chipNotes = findViewById(R.id.chip_sec_notes);
        View chipEmails = findViewById(R.id.chip_sec_emails);
        View chipJd = findViewById(R.id.chip_sec_jd);

        ScrollView scrollView = findViewById(R.id.sv_overlay_scroll);

        if (chipSummary != null && scrollView != null) {
            chipSummary.setOnClickListener(v -> scrollView.smoothScrollTo(0, 0));
        }
        if (chipNotes != null && tvNotesTimeline != null && scrollView != null) {
            chipNotes.setOnClickListener(v -> scrollView.smoothScrollTo(0, tvNotesTimeline.getTop()));
        }
        if (chipEmails != null && cardAttachedEmails != null && scrollView != null) {
            chipEmails.setOnClickListener(v -> scrollView.smoothScrollTo(0, cardAttachedEmails.getTop()));
        }
        if (chipJd != null && cardJd != null && scrollView != null) {
            chipJd.setOnClickListener(v -> scrollView.smoothScrollTo(0, cardJd.getTop()));
        }
    }

    private void renderNoteSection(String title, List<CallNote> notes, android.widget.LinearLayout parent) {
        if (notes == null || notes.isEmpty() || parent == null) return;

        TextView headerTv = new TextView(this);
        headerTv.setText(title);
        headerTv.setTextColor(0xFF818CF8);
        headerTv.setTextSize(13);
        headerTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        headerTv.setPadding(0, 16, 0, 8);
        parent.addView(headerTv);

        for (CallNote note : notes) {
            String noteClean = cleanNoteText(note.note);
            if (noteClean.trim().isEmpty()) {
                noteClean = note.note == null ? "" : note.note;
            }
            if (noteClean.trim().isEmpty()) continue;

            android.widget.LinearLayout itemContainer = new android.widget.LinearLayout(this);
            itemContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
            itemContainer.setPadding(24, 16, 24, 16);

            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, 16);
            itemContainer.setLayoutParams(params);

            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0x221E1B4B);
            float density = getResources().getDisplayMetrics().density;
            bg.setCornerRadius(10 * density);
            bg.setStroke((int) (1 * density), 0x336366F1);
            itemContainer.setBackground(bg);

            android.widget.LinearLayout titleRow = new android.widget.LinearLayout(this);
            titleRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            titleRow.setPadding(0, 0, 0, 6);

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
            String timeStr = sdf.format(new java.util.Date(note.timestamp));

            TextView timeTv = new TextView(this);
            timeTv.setText(timeStr);
            timeTv.setTextColor(0xFF9CA3AF);
            timeTv.setTextSize(11);
            timeTv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView deleteTv = new TextView(this);
            deleteTv.setText("🗑️ Delete");
            deleteTv.setTextColor(0xFFFB7185);
            deleteTv.setTextSize(12);
            deleteTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            deleteTv.setPadding(12, 4, 4, 4);
            deleteTv.setClickable(true);
            deleteTv.setFocusable(true);
            final long noteId = note.id;
            deleteTv.setOnClickListener(v -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Delete this note?")
                        .setMessage("This note will be permanently removed.")
                        .setPositiveButton("Delete", (d, w) -> {
                            new DatabaseHelper(this).deleteNote(noteId, jobCallId);
                            if (etOverlayNoteInput != null) etOverlayNoteInput.setText("");
                            lastAutoSavedNoteText = "";
                            bindNotesAndSkills();
                            Toast.makeText(this, "Note deleted", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            titleRow.addView(timeTv);
            titleRow.addView(deleteTv);

            TextView pointsTv = new TextView(this);
            StringBuilder pointsSb = new StringBuilder();
            for (String line : noteClean.split("\n")) {
                String trimmedLine = line.trim();
                if (!trimmedLine.isEmpty()) {
                    if (!trimmedLine.startsWith("•") && !trimmedLine.startsWith("-")) {
                        trimmedLine = "• " + trimmedLine;
                    }
                    if (pointsSb.length() > 0) pointsSb.append("\n");
                    pointsSb.append(trimmedLine);
                }
            }
            pointsTv.setText(pointsSb.toString());
            pointsTv.setTextColor(0xFFF3F4F6);
            pointsTv.setTextSize(13);
            pointsTv.setLineSpacing(0f, 1.2f);

            itemContainer.addView(titleRow);
            itemContainer.addView(pointsTv);
            parent.addView(itemContainer);
        }
    }

    private void showEmailDetailDialog(EmailMessage email) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_mail_detail, null);
        builder.setView(view);

        Dialog dialog = builder.create();

        TextView tvSubject = view.findViewById(R.id.tv_detail_subject);
        TextView tvFrom = view.findViewById(R.id.tv_detail_from);
        TextView tvTo = view.findViewById(R.id.tv_detail_to);
        TextView tvDate = view.findViewById(R.id.tv_detail_date);
        TextView tvBody = view.findViewById(R.id.tv_detail_body);
        WebView webView = view.findViewById(R.id.webview_detail_body);
        ImageView btnClose = view.findViewById(R.id.btn_close_detail);
        View btnAssign = view.findViewById(R.id.btn_detail_assign);

        if (tvSubject != null) tvSubject.setText(email.getSubject());
        if (tvFrom != null) tvFrom.setText("From: " + email.getSender());
        if (tvTo != null) tvTo.setText("To: " + (email.getRecipient().isEmpty() ? GmailService.getAccountEmail(this) : email.getRecipient()));

        if (tvDate != null) {
            long ts = email.getReceivedTimestamp();
            if (ts > 0) {
                tvDate.setText("Date: " + DateFormat.format("E, dd MMM yyyy, h:mm a", new java.util.Date(ts)));
            }
        }

        String body = email.getBody();
        if (body == null || body.trim().isEmpty()) {
            body = email.getSnippet();
        }

        if (body != null && !body.trim().isEmpty()) {
            boolean isHtml = body.contains("<html") || body.contains("<div") || body.contains("<p>") || body.contains("<span") || body.contains("<br") || body.contains("<table");
            if (isHtml && webView != null) {
                webView.setVisibility(View.VISIBLE);
                if (tvBody != null) tvBody.setVisibility(View.GONE);

                WebSettings webSettings = webView.getSettings();
                webSettings.setJavaScriptEnabled(true);
                webSettings.setDomStorageEnabled(true);
                webSettings.setLoadWithOverviewMode(true);
                webSettings.setUseWideViewPort(true);

                String styledHtml = "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><style>" +
                        "body { color: #E0E0E0; background-color: #121212; font-family: sans-serif; font-size: 14px; padding: 12px; line-height: 1.6; word-wrap: break-word; }" +
                        "a { color: #818CF8; }" +
                        "img { max-width: 100% !important; height: auto !important; }" +
                        "table { max-width: 100% !important; width: 100% !important; }" +
                        "</style></head><body>" + body + "</body></html>";

                webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null);
            } else if (tvBody != null) {
                tvBody.setText(isHtml ? Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY) : body);
                tvBody.setVisibility(View.VISIBLE);
                if (webView != null) webView.setVisibility(View.GONE);
            }
        } else {
            if (tvBody != null) {
                tvBody.setText("(No email content available)");
                tvBody.setVisibility(View.VISIBLE);
            }
            if (webView != null) webView.setVisibility(View.GONE);
        }

        View btnUnassign = view.findViewById(R.id.btn_detail_unassign);
        if (email.getJobCallId() > 0 && btnUnassign != null) {
            btnUnassign.setVisibility(View.VISIBLE);
            btnUnassign.setOnClickListener(v -> {
                DatabaseHelper db = new DatabaseHelper(this);
                db.unassignJobEmail(email.getId());
                Toast.makeText(this, "Email unassigned from call log!", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                bindNotesAndSkills();
            });
        }

        if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
        if (btnAssign != null) btnAssign.setVisibility(View.GONE);

        dialog.show();
    }

    private TextView tvCallerStatusRef() {
        return findViewById(R.id.tv_overlay_caller_status);
    }

    private void bindNoteEditor(TextView tvNotesTimeline, TextView tvCallerStatus) {
        tvNotesTimelineField = tvNotesTimeline;
        tvCallerStatusField = tvCallerStatus;
        etOverlayNoteInput = findViewById(R.id.et_overlay_note_input);
        etOverlayPhone = findViewById(R.id.et_overlay_phone_number);
        if (etOverlayPhone != null && notEmpty(phoneNumber)) {
            etOverlayPhone.setText(phoneNumber);
        }
        etOverlayName = findViewById(R.id.et_overlay_name);
        etOverlayCompany = findViewById(R.id.et_overlay_company);
        etOverlayExpectedCtc = findViewById(R.id.et_overlay_expected_ctc);
        etOverlayNextCall = findViewById(R.id.et_overlay_next_call);
        TextInputLayout tilOverlayNextCall = findViewById(R.id.til_overlay_next_call);
        spinnerOverlayRound = findViewById(R.id.spinner_overlay_round);
        View btnOverlayCancelNote = findViewById(R.id.btn_overlay_cancel_note);
        View btnOverlaySaveNote = findViewById(R.id.btn_overlay_save_note);

        View btnMicDictate = findViewById(R.id.btn_mic_dictate);
        if (btnMicDictate != null) {
            btnMicDictate.setOnClickListener(v -> startVoiceDictation());
        }

        View btnPickRecording = findViewById(R.id.btn_pick_recording_file);
        if (btnPickRecording != null) {
            btnPickRecording.setOnClickListener(v -> pickAudioRecordingFile());
        }

        // Prefill from the latest DB state (falls back to what CallSaverInCallService passed in).
        JobCall currentForPrefill = jobCallId != -1 ? new DatabaseHelper(this).getJobCallById(jobCallId) : null;
        String prefillName = currentForPrefill != null ? currentForPrefill.getRecruiterName() : recruiter;
        String prefillCompany = currentForPrefill != null ? currentForPrefill.getCompanyName() : company;
        String prefillCtc = currentForPrefill != null ? currentForPrefill.getExpectedCtc() : "";
        String prefillNextCall = currentForPrefill != null ? currentForPrefill.getTentativeSchedule() : "";
        String prefillRound = currentForPrefill != null ? currentForPrefill.getRoundStatus() : roundStatus;
        tagsValue = currentForPrefill != null && notEmpty(currentForPrefill.getTags()) ? currentForPrefill.getTags() : tags;
        if (tagsValue == null) tagsValue = "";
        renderTagsRow();

        java.util.Arrays.fill(jdPaths, null);
        String prefillJd = currentForPrefill != null ? currentForPrefill.getJdImagePath() : null;
        if (notEmpty(prefillJd)) {
            String[] split = prefillJd.split(",");
            for (int i = 0; i < split.length && i < 3; i++) {
                String p = split[i].trim();
                if (!p.isEmpty()) jdPaths[i] = p;
            }
        }
        renderJdRow();

        if (etOverlayName != null) etOverlayName.setText(prefillName);
        if (etOverlayCompany != null) etOverlayCompany.setText(prefillCompany);
        if (etOverlayExpectedCtc != null) etOverlayExpectedCtc.setText(prefillCtc);
        if (etOverlayNextCall != null) {
            etOverlayNextCall.setText(prefillNextCall);
            etOverlayNextCall.setOnClickListener(v -> showDateTimePicker(etOverlayNextCall));
        }
        if (tilOverlayNextCall != null && etOverlayNextCall != null) {
            tilOverlayNextCall.setEndIconOnClickListener(v -> etOverlayNextCall.setText(""));
        }

        if (spinnerOverlayRound != null) {
            ArrayAdapter<String> roundAdapter = new ArrayAdapter<>(this,
                    R.layout.item_spinner_white,
                    new String[]{"First time", "1st Round", "2nd Round", "Final Round", "HR / Salary", "Offered", "Not Interested", "Negative"});
            roundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerOverlayRound.setAdapter(roundAdapter);
            if (prefillRound != null) {
                for (int i = 0; i < roundAdapter.getCount(); i++) {
                    if (roundAdapter.getItem(i).equalsIgnoreCase(prefillRound)) {
                        spinnerOverlayRound.setSelection(i);
                        break;
                    }
                }
            }
        }
        prefillRoundPosition = spinnerOverlayRound != null ? spinnerOverlayRound.getSelectedItemPosition() : -1;
        lastAutoSavedNoteText = "";

        if (btnOverlayCancelNote != null && llOverlayEditPanel != null) {
            btnOverlayCancelNote.setOnClickListener(v -> {
                // Flush first so nothing typed in the last second is lost, then actually
                // leave - in review mode there's no call to go back to, so close the screen.
                // Wrapped in try/catch so a save error can never block leaving the screen.
                autoSaveHandler.removeCallbacks(autoSaveRunnable);
                try {
                    persistNoteAndDetails(false);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (reviewMode) {
                    finish();
                } else {
                    llOverlayEditPanel.setVisibility(View.GONE);
                }
            });
        }

        View chipRole = findViewById(R.id.chip_preset_role);
        View chipHybrid = findViewById(R.id.chip_preset_hybrid);
        View chipC2h = findViewById(R.id.chip_preset_c2h);
        View chipFulltime = findViewById(R.id.chip_preset_fulltime);
        View chipInterested = findViewById(R.id.chip_preset_interested);
        View chipInterviewScheduled = findViewById(R.id.chip_preset_interview_scheduled);
        View chipRoundCleared = findViewById(R.id.chip_preset_round_cleared);

        // Chip taps add a tag (shown as its own box next to the call status), never
        // appended into the free-text note - these are quick facts, not notes.
        View.OnClickListener chipClickListener = v -> {
            if (v instanceof com.google.android.material.chip.Chip) {
                addTag(((com.google.android.material.chip.Chip) v).getText().toString());
            }
        };
        if (chipRole != null) chipRole.setOnClickListener(chipClickListener);
        if (chipHybrid != null) chipHybrid.setOnClickListener(chipClickListener);
        if (chipC2h != null) chipC2h.setOnClickListener(chipClickListener);
        if (chipFulltime != null) chipFulltime.setOnClickListener(chipClickListener);
        if (chipInterested != null) chipInterested.setOnClickListener(chipClickListener);
        if (chipInterviewScheduled != null) chipInterviewScheduled.setOnClickListener(chipClickListener);
        if (chipRoundCleared != null) chipRoundCleared.setOnClickListener(chipClickListener);

        // bindNoteEditor() can run more than once (e.g. after deleting a note refreshes
        // the whole panel) - guard so we don't stack duplicate TextWatchers on re-bind.
        if (etOverlayNoteInput != null && etOverlayNoteInput.getTag() == null) {
            etOverlayNoteInput.setTag("watched");
            etOverlayNoteInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    autoSaveHandler.removeCallbacks(autoSaveRunnable);
                    autoSaveHandler.postDelayed(autoSaveRunnable, 1200);
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {}
            });
        }

        if (btnOverlaySaveNote != null && llOverlayEditPanel != null && etOverlayNoteInput != null) {
            btnOverlaySaveNote.setOnClickListener(v -> {
                autoSaveHandler.removeCallbacks(autoSaveRunnable);
                // Save just persists and confirms - it stays on this screen (doesn't
                // close it) so the user can keep adding details. Use Cancel/back/X to
                // actually leave.
                try {
                    persistNoteAndDetails(true);
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(this, "Saved with an error, please check Tracker", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /** Adds a chip-picked tag (dedup, case-insensitive), re-renders the tag boxes, and autosaves. */
    private void addTag(String tag) {
        List<String> current = new ArrayList<>();
        if (notEmpty(tagsValue)) {
            for (String t : tagsValue.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) current.add(trimmed);
            }
        }
        boolean exists = false;
        for (String t : current) {
            if (t.equalsIgnoreCase(tag)) { exists = true; break; }
        }
        if (!exists) current.add(tag);
        tagsValue = android.text.TextUtils.join(", ", current);
        renderTagsRow();
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        autoSaveHandler.postDelayed(autoSaveRunnable, 400);
    }

    /** Removes a tag (tap-to-remove), re-renders the tag boxes, and autosaves. */
    private void removeTag(String tag) {
        List<String> current = new ArrayList<>();
        if (notEmpty(tagsValue)) {
            for (String t : tagsValue.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase(tag)) current.add(trimmed);
            }
        }
        tagsValue = android.text.TextUtils.join(", ", current);
        renderTagsRow();
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        autoSaveHandler.postDelayed(autoSaveRunnable, 400);
    }

    /**
     * Renders tagsValue as Material Chips under the caller name - a ChipGroup wraps
     * onto multiple lines on its own, so every tag is visible without swiping.
     */
    private void renderTagsRow() {
        com.google.android.material.chip.ChipGroup chipGroup = findViewById(R.id.ll_overlay_tags_row);
        if (chipGroup == null) return;
        chipGroup.removeAllViews();

        if (notEmpty(tagsValue)) {
            for (String tag : tagsValue.split(",")) {
                String trimmed = tag.trim();
                if (trimmed.isEmpty()) continue;
                com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
                chip.setText(trimmed);
                chip.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.white_constant));
                chip.setChipBackgroundColor(androidx.core.content.ContextCompat.getColorStateList(this, R.color.glass_fill_strong));
                chip.setChipStrokeColorResource(R.color.glass_stroke);
                chip.setChipStrokeWidth(1f);
                chip.setCloseIconEnabled(true);
                chip.setCloseIconTint(androidx.core.content.ContextCompat.getColorStateList(this, R.color.white_constant));
                // Tap the body to rename, tap the close (x) icon to remove.
                chip.setOnClickListener(v -> promptEditTag(trimmed));
                chip.setOnCloseIconClickListener(v -> removeTag(trimmed));
                chipGroup.addView(chip);
            }
        }

        // Always-present "+ Tag" chip so a tag can be added manually, not just via the
        // preset chips further down in the note editor.
        com.google.android.material.chip.Chip addTag = new com.google.android.material.chip.Chip(this);
        addTag.setText("+ Tag");
        addTag.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.accent_glow));
        addTag.setChipBackgroundColor(androidx.core.content.ContextCompat.getColorStateList(this, R.color.glass_fill));
        addTag.setChipStrokeColorResource(R.color.accent_glow);
        addTag.setChipStrokeWidth(1f);
        addTag.setOnClickListener(v -> promptAddTag());
        chipGroup.addView(addTag);

        chipGroup.setVisibility(View.VISIBLE);
    }

    /** Simple text-input dialog for adding a brand-new custom tag. */
    private void promptAddTag() {
        EditText input = new EditText(this);
        input.setHint("Tag name");
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Add tag")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) addTag(value);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Renaming a tag = remove the old text, add the edited one. */
    private void promptEditTag(String currentTag) {
        EditText input = new EditText(this);
        input.setText(currentTag);
        input.setSelection(input.getText().length());
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Edit tag")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String value = input.getText().toString().trim();
                    removeTag(currentTag);
                    if (!value.isEmpty()) addTag(value);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Renders up to 3 JD screenshot thumbnails plus an "Add" tile if there's room. */
    private void renderJdRow() {
        android.widget.LinearLayout row = findViewById(R.id.ll_overlay_jd_row);
        if (row == null) return;
        row.removeAllViews();
        int density = Math.round(getResources().getDisplayMetrics().density);
        int size = 64 * density;
        int margin = 8 * density;

        for (int i = 0; i < 3; i++) {
            final int index = i;
            String path = jdPaths[i];
            android.widget.FrameLayout tile = new android.widget.FrameLayout(this);
            android.widget.LinearLayout.LayoutParams tileLp = new android.widget.LinearLayout.LayoutParams(size, size);
            tileLp.setMarginEnd(margin);
            tile.setLayoutParams(tileLp);

            if (notEmpty(path)) {
                ImageView thumb = new ImageView(this);
                thumb.setLayoutParams(new android.widget.FrameLayout.LayoutParams(size, size));
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackgroundResource(R.drawable.bg_glass_card);
                try {
                    thumb.setImageURI(android.net.Uri.fromFile(new java.io.File(path)));
                } catch (Exception ignored) {
                }
                thumb.setClickable(true);
                thumb.setOnClickListener(v -> showFullScreenImage(path));
                tile.addView(thumb);

                TextView remove = new TextView(this);
                remove.setText("✕");
                remove.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.white_constant));
                remove.setTextSize(12);
                remove.setBackgroundResource(R.drawable.bg_glass_circle);
                remove.setGravity(android.view.Gravity.CENTER);
                int removeSize = 22 * density;
                android.widget.FrameLayout.LayoutParams removeLp = new android.widget.FrameLayout.LayoutParams(removeSize, removeSize);
                removeLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
                remove.setLayoutParams(removeLp);
                remove.setClickable(true);
                remove.setOnClickListener(v -> {
                    jdPaths[index] = null;
                    renderJdRow();
                    autoSaveHandler.removeCallbacks(autoSaveRunnable);
                    autoSaveHandler.postDelayed(autoSaveRunnable, 400);
                });
                tile.addView(remove);
            } else {
                TextView addTile = new TextView(this);
                addTile.setText("+");
                addTile.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary));
                addTile.setTextSize(24);
                addTile.setGravity(android.view.Gravity.CENTER);
                addTile.setBackgroundResource(R.drawable.bg_glass_field);
                addTile.setLayoutParams(new android.widget.FrameLayout.LayoutParams(size, size));
                addTile.setClickable(true);
                addTile.setOnClickListener(v -> pickJdScreenshot());
                tile.addView(addTile);
            }
            row.addView(tile);
        }
    }

    private void pickJdScreenshot() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQ_CODE_PICK_JD_SCREENSHOT);
        } catch (Exception e) {
            Toast.makeText(this, "No image picker found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_PICK_JD_SCREENSHOT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            String path = copyUriToInternalStorage(data.getData());
            if (path != null) {
                for (int i = 0; i < 3; i++) {
                    if (jdPaths[i] == null) {
                        jdPaths[i] = path;
                        break;
                    }
                }
                renderJdRow();
                autoSaveHandler.removeCallbacks(autoSaveRunnable);
                autoSaveHandler.postDelayed(autoSaveRunnable, 400);
            } else {
                Toast.makeText(this, "Couldn't load that image", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_CODE_SPEECH_INPUT && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                String spokenText = result.get(0);
                if (etOverlayNoteInput != null) {
                    String existing = etOverlayNoteInput.getText().toString();
                    if (!existing.trim().isEmpty()) {
                        etOverlayNoteInput.setText(existing + " " + spokenText);
                    } else {
                        etOverlayNoteInput.setText(spokenText);
                    }
                    etOverlayNoteInput.setSelection(etOverlayNoteInput.getText().length());
                }
            }
        } else if (requestCode == REQ_CODE_PICK_AUDIO_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            processAudioRecordingFile(data.getData());
        }
    }

    private String copyUriToInternalStorage(android.net.Uri uri) {
        try {
            java.io.InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            java.io.File dir = new java.io.File(getFilesDir(), "jd_screenshots");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "jd_" + System.currentTimeMillis() + ".png");
            java.io.FileOutputStream os = new java.io.FileOutputStream(file);
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.close();
            is.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Full-screen JD screenshot viewer with pinch-to-zoom and a close button. */
    private void showFullScreenImage(String path) {
        if (path == null || path.isEmpty()) return;
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_full_screen_image);
        ImageView iv = dialog.findViewById(R.id.iv_full_screen);
        View btnClose = dialog.findViewById(R.id.btn_close_full_screen);
        iv.setImageURI(android.net.Uri.fromFile(new java.io.File(path)));
        if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());

        final float[] scaleFactor = {1.0f};
        final float[] lastTouchX = {0f};
        final float[] lastTouchY = {0f};
        final float[] posX = {0f};
        final float[] posY = {0f};
        android.view.ScaleGestureDetector scaleDetector = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector detector) {
                        scaleFactor[0] *= detector.getScaleFactor();
                        scaleFactor[0] = Math.max(0.5f, Math.min(scaleFactor[0], 5.0f));
                        iv.setScaleX(scaleFactor[0]);
                        iv.setScaleY(scaleFactor[0]);
                        return true;
                    }
                });
        iv.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    lastTouchX[0] = event.getX();
                    lastTouchY[0] = event.getY();
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (scaleFactor[0] > 1.0f) {
                        posX[0] += event.getX() - lastTouchX[0];
                        posY[0] += event.getY() - lastTouchY[0];
                        iv.setTranslationX(posX[0]);
                        iv.setTranslationY(posY[0]);
                        lastTouchX[0] = event.getX();
                        lastTouchY[0] = event.getY();
                    }
                    break;
            }
            return true;
        });
        dialog.show();
    }

    /**
     * Saves whatever is currently in the note-editor fields. Called both from the Save
     * button (with UI feedback + closes the panel) and silently - debounced while typing,
     * and immediately when the call ends - so notes are never lost just because the call
     * disconnected before the user tapped Save.
     */
    private void persistNoteAndDetails(boolean showFeedback) {
        if (etOverlayNoteInput == null) return;
        String noteText = etOverlayNoteInput.getText().toString().trim();
        String nameVal = etOverlayName != null ? etOverlayName.getText().toString().trim() : "";
        String companyVal = etOverlayCompany != null ? etOverlayCompany.getText().toString().trim() : "";
        String ctcVal = etOverlayExpectedCtc != null ? etOverlayExpectedCtc.getText().toString().trim() : "";
        String nextCallVal = etOverlayNextCall != null ? etOverlayNextCall.getText().toString().trim() : "";
        String selectedRound = spinnerOverlayRound != null && spinnerOverlayRound.getSelectedItem() != null
                ? spinnerOverlayRound.getSelectedItem().toString() : "First time";

        // Nothing typed and nothing to update - skip silent auto-saves so we don't create
        // an empty lead just because the panel was opened and closed.
        boolean hasAnyDetail = !noteText.isEmpty() || !nameVal.isEmpty() || !companyVal.isEmpty()
                || !ctcVal.isEmpty() || !nextCallVal.isEmpty() || notEmpty(tagsValue);
        if (!showFeedback && !hasAnyDetail) return;
        // Nothing changed since the last auto-save - avoid inserting duplicate note rows.
        if (!showFeedback && noteText.equals(lastAutoSavedNoteText) && jobCallId != -1) return;

        // The user explicitly picking a round in this dropdown always wins over
        // whatever the AI later infers from the note text.
        boolean userChangedRound = spinnerOverlayRound != null
                && spinnerOverlayRound.getSelectedItemPosition() != prefillRoundPosition;

        DatabaseHelper db = new DatabaseHelper(this);
        long targetJobId = jobCallId;

        StringBuilder jdJoined = new StringBuilder();
        for (String p : jdPaths) {
            if (p != null && !p.trim().isEmpty()) {
                if (jdJoined.length() > 0) jdJoined.append(",");
                jdJoined.append(p.trim());
            }
        }
        String jdImagePathVal = jdJoined.toString();

        if (etOverlayPhone != null) {
            String typedPhone = etOverlayPhone.getText().toString().trim();
            if (!typedPhone.isEmpty()) {
                phoneNumber = typedPhone;
            }
        }

        if (targetJobId == -1) {
            String leadCompany = !companyVal.isEmpty() ? companyVal
                    : (notEmpty(contactName) ? contactName : "");
            JobCall newLead = new JobCall(phoneNumber, leadCompany, selectedRound, tagsValue, noteText, 0, System.currentTimeMillis());
            newLead.setRecruiterName(nameVal);
            newLead.setExpectedCtc(ctcVal);
            newLead.setTentativeSchedule(nextCallVal);
            newLead.setJdImagePath(jdImagePathVal);
            targetJobId = db.insertJobCall(newLead);
            jobCallId = targetJobId;
            company = leadCompany;
            recruiter = nameVal;
            if (notEmpty(phoneNumber)) {
                db.linkPhoneToJob(targetJobId, phoneNumber, nameVal, true);
            }
        } else {
            JobCall current = db.getJobCallById(targetJobId);
            if (current != null) {
                current.setRecruiterName(nameVal);
                current.setCompanyName(companyVal);
                current.setExpectedCtc(ctcVal);
                current.setTentativeSchedule(nextCallVal);
                current.setRoundStatus(selectedRound);
                current.setTags(tagsValue);
                current.setJdImagePath(jdImagePathVal);
                db.updateJobCall(current);
                if (notEmpty(phoneNumber)) {
                    db.linkPhoneToJob(targetJobId, phoneNumber, nameVal, true);
                }
                company = current.getCompanyName();
                recruiter = current.getRecruiterName();
            } else {
                db.updateRoundStatus(targetJobId, selectedRound);
            }
        }

        if (!noteText.isEmpty() && !noteText.equals(lastAutoSavedNoteText)) {
            db.insertNote(targetJobId, noteText, System.currentTimeMillis(), DatabaseHelper.NOTE_SOURCE_MANUAL);
        }
        db.refreshNotesPreview(targetJobId);
        lastAutoSavedNoteText = noteText;

        if (!noteText.isEmpty()) {
            final long finalJobId = targetJobId;
            // Feed the AI the full note history for this lead, not just the latest
            // snippet - round/CTC/work-mode/agenda can be mentioned in an earlier call
            // and this way a later save won't lose track of it.
            List<CallNote> allNotesForAi = db.getNotesForJob(finalJobId);
            StringBuilder combinedTranscript = new StringBuilder();
            List<CallNote> chronological = new ArrayList<>(allNotesForAi);
            Collections.reverse(chronological);
            for (CallNote n : chronological) {
                if (n.note == null || n.note.trim().isEmpty()) continue;
                if (combinedTranscript.length() > 0) combinedTranscript.append("\n");
                combinedTranscript.append(n.note.trim());
            }
            String transcriptForAi = combinedTranscript.length() > 0 ? combinedTranscript.toString() : noteText;
            OpenAiClient.extractFields(this, transcriptForAi, new OpenAiClient.OpenAiCallback() {
                @Override
                public void onSuccess(org.json.JSONObject result) {
                    try {
                        JobCall existingCall = db.getJobCallById(finalJobId);
                        if (existingCall != null) {
                            String sched = result.optString("tentative_schedule", "").trim();
                            if (!sched.isEmpty() && nextCallVal.isEmpty()) {
                                existingCall.setTentativeSchedule(sched);
                            }
                            if (!userChangedRound) {
                                String round = OpenAiClient.normalizeRoundStatus(result.optString("present_round", ""), selectedRound);
                                if (OpenAiClient.shouldUpdateRoundStatus(existingCall.getRoundStatus(), round)) {
                                    existingCall.setRoundStatus(round);
                                }
                            }
                            String agenda = result.optString("main_agenda", "").trim();
                            if (!agenda.isEmpty()) existingCall.setMainAgenda(agenda);
                            // Fill-if-blank: never overwrite what the user explicitly typed/picked.
                            String ctc = result.optString("expected_ctc", "").trim();
                            if (!ctc.isEmpty() && existingCall.getExpectedCtc().isEmpty()) existingCall.setExpectedCtc(ctc);
                            String wm = result.optString("work_mode", "").trim();
                            if (!wm.isEmpty() && existingCall.getWorkMode().isEmpty()) existingCall.setWorkMode(wm);
                            String et = result.optString("employment_type", "").trim();
                            if (!et.isEmpty() && existingCall.getEmploymentType().isEmpty()) existingCall.setEmploymentType(et);
                            db.updateJobCall(existingCall);
                            FollowUpNotifier.checkAndNotify(InCallActivity.this);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onError(String error) {
                }
            });
        }

        List<CallNote> freshNotesList = db.getNotesForJob(targetJobId);
        if (!freshNotesList.isEmpty() && tvNotesTimelineField != null) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (CallNote note : freshNotesList) {
                if (count >= 5) break;
                count++;
                sb.append("• ").append(note.note).append("\n");
            }
            if (sb.length() > 0) sb.setLength(sb.length() - 1);
            tvNotesTimelineField.setText(sb.toString());
        }
        if (tvCallerStatusField != null) tvCallerStatusField.setText("Stage: " + selectedRound);
        bindCallerInfo();

        if (showFeedback) {
            // Save confirms and stays on this screen (doesn't close the panel or the
            // activity) - Cancel/back/X are what actually leave.
            etOverlayNoteInput.setText("");
            Toast.makeText(this, "Updated", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDateTimePicker(final EditText etTarget) {
        final Calendar calendar = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            calendar.set(Calendar.YEAR, year);
            calendar.set(Calendar.MONTH, month);
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);

            new TimePickerDialog(this, (timeView, hourOfDay, minute) -> {
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
                calendar.set(Calendar.MINUTE, minute);
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd 'at' hh:mm a", Locale.getDefault());
                etTarget.setText(format.format(calendar.getTime()));
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show();
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private String cleanNoteText(String rawText) {
        if (rawText == null) return "";
        String[] lines = rawText.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim().toLowerCase();
            if (trimmed.contains("interested in") ||
                    trimmed.contains("is interested") ||
                    trimmed.contains("of course") ||
                    trimmed.contains("candidate is interested") ||
                    trimmed.contains("ofcourse")) {
                continue;
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString();
    }

    private static final int REQ_CODE_SPEECH_INPUT = 9002;
    private static final int REQ_CODE_PICK_AUDIO_FILE = 9005;

    private void startVoiceDictation() {
        Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Dictate call note...");
        try {
            startActivityForResult(intent, REQ_CODE_SPEECH_INPUT);
        } catch (Exception e) {
            Toast.makeText(this, "Voice dictation is not supported on this device.", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickAudioRecordingFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Select Call Recording"), REQ_CODE_PICK_AUDIO_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "Could not launch audio file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }



    private void processAudioRecordingFile(Uri uri) {
        if (uri == null) return;
        Toast.makeText(this, "⚡ Transcribing audio file via Deepgram...", Toast.LENGTH_LONG).show();

        new Thread(() -> {
            try {
                java.io.InputStream inputStream = getContentResolver().openInputStream(uri);
                if (inputStream == null) return;

                File cacheDir = getCacheDir();
                File tempAudioFile = new File(cacheDir, "manual_recording_" + System.currentTimeMillis() + ".m4a");
                java.io.FileOutputStream outputStream = new java.io.FileOutputStream(tempAudioFile);

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.close();
                inputStream.close();

                runOnUiThread(() -> {
                    Transcriber.transcribeCallRecording(InCallActivity.this, tempAudioFile, new Transcriber.TranscriptionCallback() {
                        @Override
                        public void onSuccess(String transcriptText) {
                            if (transcriptText == null || transcriptText.trim().isEmpty()) {
                                Toast.makeText(InCallActivity.this, "Audio file was silent or empty.", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            Toast.makeText(InCallActivity.this, "Extracting summary via OpenAI...", Toast.LENGTH_SHORT).show();
                            OpenAiClient.extractFields(InCallActivity.this, "Call Transcript:\n" + transcriptText, new OpenAiClient.OpenAiCallback() {
                                @Override
                                public void onSuccess(JSONObject result) {
                                    if (result == null) return;
                                    String comp = result.optString("company_name", "").trim();
                                    String role = result.optString("applied_role", "").trim();
                                    String rec = result.optString("recruiter_name", "").trim();
                                    JSONArray points = result.optJSONArray("key_discussion_points");

                                    if (!comp.isEmpty() && !"null".equalsIgnoreCase(comp) && etOverlayCompany != null) etOverlayCompany.setText(comp);
                                    if (!rec.isEmpty() && !"null".equalsIgnoreCase(rec) && etOverlayName != null) etOverlayName.setText(rec);

                                    StringBuilder sb = new StringBuilder();
                                    if (points != null && points.length() > 0) {
                                        for (int i = 0; i < points.length(); i++) {
                                            String pt = points.optString(i, "").trim();
                                            if (!pt.isEmpty()) {
                                                if (sb.length() > 0) sb.append("\n");
                                                sb.append("• ").append(pt);
                                            }
                                        }
                                    }
                                    if (sb.length() > 0 && etOverlayNoteInput != null) {
                                        String existing = etOverlayNoteInput.getText().toString();
                                        if (!existing.trim().isEmpty()) {
                                            etOverlayNoteInput.setText(existing + "\n" + sb.toString());
                                        } else {
                                            etOverlayNoteInput.setText(sb.toString());
                                        }
                                    }
                                    persistNoteAndDetails(true);
                                    Toast.makeText(InCallActivity.this, "✅ Audio recording transcribed & notes saved!", Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(String error) {
                                    Toast.makeText(InCallActivity.this, "OpenAI summary error: " + error, Toast.LENGTH_SHORT).show();
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            Toast.makeText(InCallActivity.this, "Deepgram transcription error: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(InCallActivity.this, "Error reading audio file: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String getOrdinalSuffix(int number) {
        if (number >= 11 && number <= 13) return "th";
        switch (number % 10) {
            case 1: return "st";
            case 2: return "nd";
            case 3: return "rd";
            default: return "th";
        }
    }
}

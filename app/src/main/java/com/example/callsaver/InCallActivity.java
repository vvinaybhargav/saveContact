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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputLayout;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
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

    // Note-editor fields, promoted to instance state so notes can auto-save (debounced
    // while typing, and immediately when the call ends / screen is destroyed) instead
    // of requiring an explicit Save tap that's easy to miss before a call disconnects.
    private EditText etOverlayNoteInput;
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
        // Review-mode screens (Tracker/Upcoming/"+"/post-call notification) have no live
        // call to return to - make sure back always actually leaves instead of getting
        // stuck, same as the Cancel button.
        if (reviewMode) {
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

    private void updateMuteSpeakerUi() {
        boolean muted = CallSaverInCallService.isMuted();
        boolean speaker = CallSaverInCallService.isSpeakerOn();
        if (tvMuteLabel != null) tvMuteLabel.setText(muted ? "Muted" : "Mute");
        if (tvSpeakerLabel != null) tvSpeakerLabel.setText(speaker ? "Speaker On" : "Speaker");
        int activeColor = androidx.core.content.ContextCompat.getColor(this, R.color.accent_indigo);
        int inactiveColor = android.graphics.Color.parseColor("#FFFFFF");
        if (btnToggleMute != null) btnToggleMute.setColorFilter(muted ? activeColor : inactiveColor);
        if (btnToggleSpeaker != null) btnToggleSpeaker.setColorFilter(speaker ? activeColor : inactiveColor);
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
            for (CallNote note : notesList) {
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
            int pointsToShow = Math.min(allCleanedPoints.size(), 3);
            for (int i = 0; i < pointsToShow; i++) {
                if (collapsedSb.length() > 0) collapsedSb.append("\n");
                collapsedSb.append(allCleanedPoints.get(i));
            }
            String collapsedStr = collapsedSb.toString();
            if (collapsedStr.isEmpty()) collapsedStr = "• No previous notes.";
            tvNotesTimeline.setText(collapsedStr);
        }

        if (llExpandedList != null && !notesList.isEmpty()) {
            llExpandedList.removeAllViews();
            List<CallNote> chronologicalNotes = new ArrayList<>(notesList);
            Collections.reverse(chronologicalNotes);

            int callCounter = 1;
            for (CallNote note : chronologicalNotes) {
                String noteClean = cleanNoteText(note.note);
                if (noteClean.trim().isEmpty()) {
                    noteClean = note.note == null ? "" : note.note;
                }
                if (noteClean.trim().isEmpty()) continue;

                android.widget.LinearLayout titleRow = new android.widget.LinearLayout(this);
                titleRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                titleRow.setPadding(0, callCounter == 1 ? 0 : 12, 0, 4);

                TextView titleTv = new TextView(this);
                titleTv.setText(callCounter + getOrdinalSuffix(callCounter) + (note.isManual() ? " MCall" : " Call"));
                titleTv.setTextColor(0xFF9A9AA2);
                titleTv.setTextSize(12);
                titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                titleTv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView deleteTv = new TextView(this);
                deleteTv.setText("Delete");
                deleteTv.setTextColor(0xFFFB7185);
                deleteTv.setTextSize(11);
                deleteTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                deleteTv.setPadding(16, 4, 4, 4);
                deleteTv.setClickable(true);
                deleteTv.setFocusable(true);
                final long noteId = note.id;
                deleteTv.setOnClickListener(v -> {
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Delete this note?")
                            .setMessage("This can't be undone.")
                            .setPositiveButton("Delete", (d, w) -> {
                                new DatabaseHelper(this).deleteNote(noteId, jobCallId);
                                // The note-input box still holds whatever was last typed
                                // there (it's separate from the history list) - clear it
                                // and the auto-save tracker too, otherwise the very next
                                // auto-save (e.g. when this screen closes) would silently
                                // re-insert the same text and "undo" the delete.
                                if (etOverlayNoteInput != null) etOverlayNoteInput.setText("");
                                lastAutoSavedNoteText = "";
                                bindNotesAndSkills();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });

                titleRow.addView(titleTv);
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
                pointsTv.setTextColor(0xFFD1D5DB);
                pointsTv.setTextSize(13);
                pointsTv.setLineSpacing(0f, 1.2f);

                llExpandedList.addView(titleRow);
                llExpandedList.addView(pointsTv);
                callCounter++;
            }
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

        bindNoteEditor(tvNotesTimeline, tvCallerStatusRef());
    }

    private TextView tvCallerStatusRef() {
        return findViewById(R.id.tv_overlay_caller_status);
    }

    private void bindNoteEditor(TextView tvNotesTimeline, TextView tvCallerStatus) {
        tvNotesTimelineField = tvNotesTimeline;
        tvCallerStatusField = tvCallerStatus;
        etOverlayNoteInput = findViewById(R.id.et_overlay_note_input);
        etOverlayName = findViewById(R.id.et_overlay_name);
        etOverlayCompany = findViewById(R.id.et_overlay_company);
        etOverlayExpectedCtc = findViewById(R.id.et_overlay_expected_ctc);
        etOverlayNextCall = findViewById(R.id.et_overlay_next_call);
        TextInputLayout tilOverlayNextCall = findViewById(R.id.til_overlay_next_call);
        spinnerOverlayRound = findViewById(R.id.spinner_overlay_round);
        View btnOverlayCancelNote = findViewById(R.id.btn_overlay_cancel_note);
        View btnOverlaySaveNote = findViewById(R.id.btn_overlay_save_note);

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
                // finally-block guarantees the screen actually closes even if saving
                // itself hits an error, instead of leaving Save looking like it did
                // nothing.
                try {
                    persistNoteAndDetails(true);
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(this, "Saved with an error, please check Tracker", Toast.LENGTH_LONG).show();
                } finally {
                    if (llOverlayEditPanel != null) llOverlayEditPanel.setVisibility(View.GONE);
                    if (reviewMode && !isFinishing()) finish();
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

    /** Renders tagsValue as a row of small tap-to-remove boxes next to the call status pill. */
    private void renderTagsRow() {
        View hsvTags = findViewById(R.id.hsv_overlay_tags);
        android.widget.LinearLayout llTagsRow = findViewById(R.id.ll_overlay_tags_row);
        if (hsvTags == null || llTagsRow == null) return;
        llTagsRow.removeAllViews();
        if (!notEmpty(tagsValue)) {
            hsvTags.setVisibility(View.GONE);
            return;
        }
        for (String tag : tagsValue.split(",")) {
            String trimmed = tag.trim();
            if (trimmed.isEmpty()) continue;
            TextView box = new TextView(this);
            box.setText(trimmed + "  ✕");
            box.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.white_constant));
            box.setTextSize(11);
            box.setBackgroundResource(R.drawable.bg_glass_card);
            box.setPadding(24, 10, 24, 10);
            box.setClickable(true);
            box.setFocusable(true);
            box.setOnClickListener(v -> removeTag(trimmed));
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(8);
            box.setLayoutParams(lp);
            llTagsRow.addView(box);
        }
        hsvTags.setVisibility(View.VISIBLE);
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

        if (targetJobId == -1) {
            // Leave company blank if it wasn't filled in - no placeholder text like
            // "Unsaved Number"; the display logic elsewhere already falls back to
            // showing just the name, or just the number, when a field is empty.
            String leadCompany = !companyVal.isEmpty() ? companyVal
                    : (notEmpty(contactName) ? contactName : "");
            JobCall newLead = new JobCall(phoneNumber, leadCompany, selectedRound, tagsValue, noteText, 0, System.currentTimeMillis());
            newLead.setRecruiterName(nameVal);
            newLead.setExpectedCtc(ctcVal);
            newLead.setTentativeSchedule(nextCallVal);
            targetJobId = db.insertJobCall(newLead);
            jobCallId = targetJobId;
            company = leadCompany;
            recruiter = nameVal;
        } else {
            JobCall current = db.getJobCallById(targetJobId);
            if (current != null) {
                if (!nameVal.isEmpty()) current.setRecruiterName(nameVal);
                if (!companyVal.isEmpty()) current.setCompanyName(companyVal);
                current.setExpectedCtc(ctcVal);
                current.setTentativeSchedule(nextCallVal);
                current.setRoundStatus(selectedRound);
                current.setTags(tagsValue);
                db.updateJobCall(current);
                company = current.getCompanyName();
                recruiter = current.getRecruiterName();
            } else {
                db.updateRoundStatus(targetJobId, selectedRound);
            }
            if (!noteText.isEmpty() && !noteText.equals(lastAutoSavedNoteText)) {
                db.insertNote(targetJobId, noteText, System.currentTimeMillis());
            }
            db.refreshNotesPreview(targetJobId);
        }
        lastAutoSavedNoteText = noteText;

        if (!noteText.isEmpty()) {
            final long finalJobId = targetJobId;
            OpenAiClient.extractFields(this, noteText, new OpenAiClient.OpenAiCallback() {
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
            if (llOverlayEditPanel != null) llOverlayEditPanel.setVisibility(View.GONE);
            etOverlayNoteInput.setText("");
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
            // In review mode there's no live call to return to - close after saving.
            if (reviewMode) finish();
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

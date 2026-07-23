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

    private TextView tvCallTimer;
    private android.widget.LinearLayout llIncomingControls;
    private android.widget.LinearLayout llActiveControls;
    private ImageView btnToggleMute;
    private TextView tvMuteLabel;
    private ImageView btnToggleSpeaker;
    private TextView tvSpeakerLabel;
    private ImageView btnToggleNote;
    private View llOverlayEditPanel;

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

        setContentView(R.layout.activity_in_call);
        instance = this;

        Intent intent = getIntent();
        phoneNumber = intent.getStringExtra("phone_number");
        company = intent.getStringExtra("company_name");
        roundStatus = intent.getStringExtra("round_status");
        tags = intent.getStringExtra("tags");
        jobCallId = intent.getLongExtra("job_call_id", -1);
        recruiter = intent.getStringExtra("recruiter_name");
        int initialState = intent.getIntExtra("initial_state", Call.STATE_ACTIVE);

        bindCallerInfo();
        bindNotesAndSkills();
        bindControls();
        applyState(initialState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (instance == this) instance = null;
        timerHandler.removeCallbacks(timerRunnable);
    }

    /** Called by CallSaverInCallService whenever the live call's state changes. */
    public static void notifyCallStateChanged(int state) {
        if (instance != null) {
            instance.runOnUiThread(() -> instance.applyState(state));
        }
    }

    public static void finishIfOpen() {
        if (instance != null) {
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
        } else if (state == Call.STATE_DISCONNECTED) {
            stopTimer();
        }
        updateMuteSpeakerUi();
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
                CallSaverInCallService.setMuted(!CallSaverInCallService.isMuted());
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

        String title;
        if (notEmpty(candidateName) && notEmpty(company)) {
            title = candidateName.trim() + " @ " + company.trim();
        } else if (notEmpty(company) && notEmpty(recruiter)) {
            title = recruiter.trim() + " @ " + company.trim();
        } else if (notEmpty(company)) {
            title = company.trim();
        } else if (notEmpty(candidateName)) {
            title = candidateName.trim();
        } else if (notEmpty(recruiter)) {
            title = recruiter.trim();
        } else {
            title = phoneNumber;
        }
        tvCallerName.setText(title);

        String statusText = notEmpty(roundStatus) ? roundStatus : "First time";
        if (notEmpty(appliedRole)) {
            statusText += " - " + appliedRole.trim() + " role";
        }
        tvCallerStatus.setText(statusText);

        String initialChar = notEmpty(company) ? String.valueOf(company.charAt(0)).toUpperCase() : "R";
        tvAvatarLetter.setText(initialChar);
        int[] avatarColors = {0xFF6366F1, 0xFF10B981, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFFF59E0B, 0xFF14B8A6};
        int colorIndex = Math.abs(title.hashCode()) % avatarColors.length;
        if (cardAvatar != null) {
            cardAvatar.setCardBackgroundColor(avatarColors[colorIndex]);
        }
    }

    private boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private void bindNotesAndSkills() {
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

                TextView titleTv = new TextView(this);
                titleTv.setText(callCounter + getOrdinalSuffix(callCounter) + (note.isManual() ? " MCall" : " Call"));
                titleTv.setTextColor(0xFF6366F1);
                titleTv.setTextSize(12);
                titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                titleTv.setPadding(0, callCounter == 1 ? 0 : 12, 0, 4);

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

                llExpandedList.addView(titleTv);
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
        EditText etOverlayNoteInput = findViewById(R.id.et_overlay_note_input);
        Spinner spinnerOverlayRound = findViewById(R.id.spinner_overlay_round);
        View btnOverlayCancelNote = findViewById(R.id.btn_overlay_cancel_note);
        View btnOverlaySaveNote = findViewById(R.id.btn_overlay_save_note);

        if (spinnerOverlayRound != null) {
            ArrayAdapter<String> roundAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{"First time", "1st Round", "2nd Round", "Final Round", "HR / Salary", "Offered", "Not Interested", "Negative"});
            roundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerOverlayRound.setAdapter(roundAdapter);
            if (roundStatus != null) {
                for (int i = 0; i < roundAdapter.getCount(); i++) {
                    if (roundAdapter.getItem(i).equalsIgnoreCase(roundStatus)) {
                        spinnerOverlayRound.setSelection(i);
                        break;
                    }
                }
            }
        }

        if (btnOverlayCancelNote != null && llOverlayEditPanel != null) {
            btnOverlayCancelNote.setOnClickListener(v -> {
                llOverlayEditPanel.setVisibility(View.GONE);
                if (etOverlayNoteInput != null) etOverlayNoteInput.setText("");
            });
        }

        View chipShortlisted = findViewById(R.id.chip_preset_shortlisted);
        View chipTmrw = findViewById(R.id.chip_preset_tmrw_interview);
        View chipNextRound = findViewById(R.id.chip_preset_next_round);
        View chipSalary = findViewById(R.id.chip_preset_salary);

        View.OnClickListener chipClickListener = v -> {
            if (v instanceof com.google.android.material.chip.Chip && etOverlayNoteInput != null) {
                String presetText = ((com.google.android.material.chip.Chip) v).getText().toString();
                String curr = etOverlayNoteInput.getText().toString();
                if (curr.trim().isEmpty()) {
                    etOverlayNoteInput.setText(presetText);
                } else {
                    etOverlayNoteInput.setText(curr.trim() + " • " + presetText);
                }
                etOverlayNoteInput.setSelection(etOverlayNoteInput.getText().length());
            }
        };
        if (chipShortlisted != null) chipShortlisted.setOnClickListener(chipClickListener);
        if (chipTmrw != null) chipTmrw.setOnClickListener(chipClickListener);
        if (chipNextRound != null) chipNextRound.setOnClickListener(chipClickListener);
        if (chipSalary != null) chipSalary.setOnClickListener(chipClickListener);

        if (btnOverlaySaveNote != null && llOverlayEditPanel != null && etOverlayNoteInput != null) {
            btnOverlaySaveNote.setOnClickListener(v -> {
                String noteText = etOverlayNoteInput.getText().toString().trim();
                String selectedRound = spinnerOverlayRound != null && spinnerOverlayRound.getSelectedItem() != null
                        ? spinnerOverlayRound.getSelectedItem().toString() : "First time";

                if (!noteText.isEmpty()) {
                    DatabaseHelper db = new DatabaseHelper(this);
                    long targetJobId = jobCallId;

                    if (targetJobId == -1) {
                        JobCall newLead = new JobCall(phoneNumber, "Unknown Recruiter", selectedRound, "", noteText, 0, System.currentTimeMillis());
                        targetJobId = db.insertJobCall(newLead);
                        jobCallId = targetJobId;
                    } else {
                        db.insertNote(targetJobId, noteText, System.currentTimeMillis());
                        db.updateRoundStatus(targetJobId, selectedRound);
                        db.refreshNotesPreview(targetJobId);
                    }

                    final long finalJobId = targetJobId;
                    OpenAiClient.extractFields(this, noteText, new OpenAiClient.OpenAiCallback() {
                        @Override
                        public void onSuccess(org.json.JSONObject result) {
                            try {
                                JobCall existingCall = db.getJobCallById(finalJobId);
                                if (existingCall != null) {
                                    String sched = result.optString("tentative_schedule", "").trim();
                                    if (!sched.isEmpty()) existingCall.setTentativeSchedule(sched);
                                    String round = OpenAiClient.normalizeRoundStatus(result.optString("present_round", ""), selectedRound);
                                    if (OpenAiClient.shouldUpdateRoundStatus(existingCall.getRoundStatus(), round)) {
                                        existingCall.setRoundStatus(round);
                                    }
                                    String agenda = result.optString("main_agenda", "").trim();
                                    if (!agenda.isEmpty()) existingCall.setMainAgenda(agenda);
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

                    List<CallNote> freshNotesList = db.getNotesForJob(targetJobId);
                    if (!freshNotesList.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        int count = 0;
                        for (CallNote note : freshNotesList) {
                            if (count >= 5) break;
                            count++;
                            sb.append("• ").append(note.note).append("\n");
                        }
                        if (sb.length() > 0) sb.setLength(sb.length() - 1);
                        tvNotesTimeline.setText(sb.toString());
                    }
                    tvCallerStatus.setText("Stage: " + selectedRound);
                }

                llOverlayEditPanel.setVisibility(View.GONE);
                etOverlayNoteInput.setText("");
                Toast.makeText(this, "Notes saved & analyzed!", Toast.LENGTH_SHORT).show();
            });
        }
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

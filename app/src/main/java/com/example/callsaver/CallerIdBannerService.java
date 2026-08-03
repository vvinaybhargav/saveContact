package com.example.callsaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Truecaller-style floating overlay banner shown over the system's own dialer during a
 * call - CallSaver no longer requires being the default dialer, so this (not
 * CallSaverInCallService, which only fires when holding that role) is what actually
 * shows caller info during a live call. Triggered by CallReceiver on ringing/active
 * call state, dismissed when the call ends.
 */
public class CallerIdBannerService extends Service {

    private static final int NOTIFICATION_ID = 5001;
    private static final String CHANNEL_ID = "caller_id_overlay_channel";
    public static final String ACTION_DISMISS = "com.example.callsaver.action.DISMISS_OVERLAY";
    public static final String ACTION_TOGGLE = "com.example.callsaver.action.TOGGLE_OVERLAY";
    public static final String ACTION_SHOW = "com.example.callsaver.action.SHOW_OVERLAY";
    public static final String ACTION_HIDE = "com.example.callsaver.action.HIDE_OVERLAY";

    private WindowManager windowManager;
    private View overlayView;
    private boolean isOverlayVisible = false;

    // Retained state for toggle/restore actions
    private String currentPhoneNumber;
    private String currentCandidateName;
    private String currentCompany;
    private String currentRoundStatus;
    private String currentRecruiter;
    private String currentCallState;
    private long currentJobCallId = -1;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        DebugLogger.log(this, "[Banner] CallerIdBannerService.onStartCommand: action=" + action);

        if (intent == null || ACTION_DISMISS.equals(action)) {
            removeOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_HIDE.equals(action)) {
            removeOverlay();
            showForegroundNotification(false);
            return START_NOT_STICKY;
        }

        if (ACTION_TOGGLE.equals(action)) {
            if (isOverlayVisible) {
                removeOverlay();
                showForegroundNotification(false);
            } else if (notEmpty(currentPhoneNumber)) {
                showOverlay(currentPhoneNumber, currentCandidateName, currentCompany, currentRoundStatus, currentRecruiter, currentJobCallId, currentCallState);
                showForegroundNotification(isOverlayVisible);
            }
            return START_NOT_STICKY;
        }

        if (ACTION_SHOW.equals(action)) {
            if (!isOverlayVisible && notEmpty(currentPhoneNumber)) {
                showOverlay(currentPhoneNumber, currentCandidateName, currentCompany, currentRoundStatus, currentRecruiter, currentJobCallId, currentCallState);
            }
            showForegroundNotification(isOverlayVisible);
            return START_NOT_STICKY;
        }

        // Fresh trigger from CallReceiver or Test Banner
        String phoneNumber = intent.getStringExtra("phone_number");
        String candidateName = intent.getStringExtra("candidate_name");
        String company = intent.getStringExtra("company_name");
        String roundStatus = intent.getStringExtra("round_status");
        long jobCallId = intent.getLongExtra("job_call_id", -1);
        String recruiter = intent.getStringExtra("recruiter_name");
        String callState = intent.getStringExtra("call_state"); // "Incoming call" / "In call"

        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            DebugLogger.log(this, "[Banner] ABORTED - no phone_number extra in intent.");
            removeOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }

        currentPhoneNumber = phoneNumber;
        currentCandidateName = candidateName;
        currentCompany = company;
        currentRoundStatus = roundStatus;
        currentRecruiter = recruiter;
        currentJobCallId = jobCallId;
        currentCallState = callState;

        showOverlay(currentPhoneNumber, currentCandidateName, currentCompany, currentRoundStatus, currentRecruiter, currentJobCallId, currentCallState);
        showForegroundNotification(isOverlayVisible);
        return START_NOT_STICKY;
    }

    private void showOverlay(String phoneNumber, String candidateName, String company, String roundStatus,
                              String recruiter, long jobCallId, String callState) {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            DebugLogger.log(this, "[Banner] ABORTED - WindowManager service unavailable.");
            stopSelf();
            return;
        }

        removeOverlay();

        android.view.ContextThemeWrapper themeWrapper =
                new android.view.ContextThemeWrapper(this, R.style.Theme_CallSaver);
        overlayView = LayoutInflater.from(themeWrapper).inflate(R.layout.layout_caller_overlay, null);

        DatabaseHelper db = new DatabaseHelper(this);
        JobCall matchedCall = db.getJobCallByNumber(this, phoneNumber);
        if (matchedCall != null) {
            if (!notEmpty(candidateName)) candidateName = matchedCall.getCandidateName();
            if (!notEmpty(company)) company = matchedCall.getCompanyName();
            if (!notEmpty(recruiter)) recruiter = matchedCall.getRecruiterName();
            if (!notEmpty(roundStatus)) roundStatus = matchedCall.getRoundStatus();
            if (jobCallId <= 0) jobCallId = (long) matchedCall.getId();
        }

        String contactName = TrackerFragment.getContactNameByNumber(this, phoneNumber);

        // Resolve display fields clearly
        String displayCandidateName;
        if (notEmpty(candidateName)) {
            displayCandidateName = candidateName.trim();
        } else if (notEmpty(contactName)) {
            displayCandidateName = contactName.trim();
        } else if (notEmpty(recruiter)) {
            displayCandidateName = recruiter.trim();
        } else if (notEmpty(company)) {
            displayCandidateName = company.trim();
        } else {
            displayCandidateName = phoneNumber;
        }

        String displayRecruiterCompany = "";
        if (notEmpty(recruiter) && notEmpty(company)) {
            displayRecruiterCompany = "Recruiter: " + recruiter.trim() + " @ " + company.trim();
        } else if (notEmpty(recruiter)) {
            displayRecruiterCompany = "Recruiter: " + recruiter.trim();
        } else if (notEmpty(company)) {
            displayRecruiterCompany = "Company: " + company.trim();
        }

        TextView tvName = overlayView.findViewById(R.id.tv_overlay_caller_name);
        TextView tvRecruiter = overlayView.findViewById(R.id.tv_overlay_caller_recruiter);
        TextView tvStatus = overlayView.findViewById(R.id.tv_overlay_caller_status);
        TextView tvAvatar = overlayView.findViewById(R.id.tv_overlay_avatar_letter);
        TextView tvNotes = overlayView.findViewById(R.id.tv_overlay_notes_summary);
        View btnLog = overlayView.findViewById(R.id.btn_overlay_expand);
        View btnClose = overlayView.findViewById(R.id.btn_overlay_close);

        if (tvName != null) tvName.setText(displayCandidateName);

        if (tvRecruiter != null) {
            if (notEmpty(displayRecruiterCompany)) {
                tvRecruiter.setText(displayRecruiterCompany);
                tvRecruiter.setVisibility(View.VISIBLE);
            } else {
                tvRecruiter.setVisibility(View.GONE);
            }
        }

        if (tvStatus != null) {
            String status = notEmpty(callState) ? callState : "Call";
            if (notEmpty(roundStatus)) status += "  •  " + roundStatus;
            status += "  •  " + phoneNumber;
            tvStatus.setText(status);
        }

        if (tvAvatar != null) {
            tvAvatar.setText(notEmpty(displayCandidateName) ? String.valueOf(displayCandidateName.charAt(0)).toUpperCase() : "?");
        }

        if (tvNotes != null) {
            String summary = buildNotesSummary(jobCallId, matchedCall);
            if (notEmpty(summary)) {
                tvNotes.setText(summary);
                tvNotes.setVisibility(View.VISIBLE);
            } else {
                tvNotes.setVisibility(View.GONE);
            }
        }

        final long finalJobCallId = jobCallId;
        final String finalPhoneNumber = phoneNumber;
        final String finalCompany = company;
        final String finalRoundStatus = roundStatus;
        final String finalRecruiter = recruiter;

        if (btnLog != null) {
            btnLog.setOnClickListener(v -> {
                Intent logIntent = new Intent(this, InCallActivity.class);
                logIntent.putExtra("mode", "review");
                logIntent.putExtra("phone_number", finalPhoneNumber);
                logIntent.putExtra("company_name", finalCompany);
                logIntent.putExtra("round_status", finalRoundStatus);
                logIntent.putExtra("recruiter_name", finalRecruiter);
                logIntent.putExtra("job_call_id", finalJobCallId);
                logIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(logIntent);
                removeOverlay();
                showForegroundNotification(false);
            });
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                removeOverlay();
                showForegroundNotification(false);
            });
        }

        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP;
        params.y = (int) (280 * getResources().getDisplayMetrics().density);
        params.x = 0;

        try {
            windowManager.addView(overlayView, params);
            isOverlayVisible = true;
            DebugLogger.log(this, "[Banner] windowManager.addView succeeded for \"" + displayCandidateName + "\".");
        } catch (Exception e) {
            e.printStackTrace();
            isOverlayVisible = false;
            DebugLogger.log(this, "[Banner] EXCEPTION on windowManager.addView: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private void removeOverlay() {
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
        isOverlayVisible = false;
    }

    private void showForegroundNotification(boolean isShowingOverlay) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Caller ID banner", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the floating caller-ID banner running during a call.");
            nm.createNotificationChannel(channel);
        }

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        Intent toggleIntent = new Intent(this, CallerIdBannerService.class).setAction(ACTION_TOGGLE);
        PendingIntent togglePendingIntent = PendingIntent.getService(this, 0, toggleIntent, piFlags);

        String title = isShowingOverlay ? "CallSaver Caller ID active" : "CallSaver Caller ID hidden";
        String text = isShowingOverlay ? "Tap to hide/restore banner" : "Caller ID hidden • Tap to show banner";

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(togglePendingIntent)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Builds a clean keyword & discussion summary (up to 4 bullet lines) for the matched job call. */
    private String buildNotesSummary(long jobCallId, JobCall call) {
        List<String> rawPoints = new ArrayList<>();

        if (call != null) {
            if (notEmpty(call.getKeyDiscussionPoints())) {
                for (String p : call.getKeyDiscussionPoints().split("\n")) {
                    if (notEmpty(p)) rawPoints.add(p.trim());
                }
            }
            if (notEmpty(call.getMainAgenda())) {
                for (String p : call.getMainAgenda().split("\n")) {
                    if (notEmpty(p)) rawPoints.add(p.trim());
                }
            }
            if (notEmpty(call.getNextSteps())) {
                for (String p : call.getNextSteps().split("\n")) {
                    if (notEmpty(p)) rawPoints.add(p.trim());
                }
            }
        }

        if (jobCallId > 0) {
            List<CallNote> notes = new DatabaseHelper(this).getNotesForJob(jobCallId);
            if (notes != null) {
                for (CallNote n : notes) {
                    if (notEmpty(n.note)) {
                        for (String rawLine : n.note.split("\n")) {
                            if (notEmpty(rawLine)) rawPoints.add(rawLine.trim());
                        }
                    }
                }
            }
        }

        if (call != null && rawPoints.isEmpty() && notEmpty(call.getNotes())) {
            for (String rawLine : call.getNotes().split("\n")) {
                if (notEmpty(rawLine)) rawPoints.add(rawLine.trim());
            }
        }

        if (rawPoints.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (String item : rawPoints) {
            if (lines >= 4) break;
            String clean = item;
            if (clean.startsWith("•") || clean.startsWith("-")) {
                clean = clean.substring(1).trim();
            }
            if (clean.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append("• ").append(clean);
            lines++;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
    }
}

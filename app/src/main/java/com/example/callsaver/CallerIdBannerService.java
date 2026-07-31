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

    private WindowManager windowManager;
    private View overlayView;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showForegroundNotification();

        if (intent == null || ACTION_DISMISS.equals(intent.getAction())) {
            removeOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }

        String phoneNumber = intent.getStringExtra("phone_number");
        String company = intent.getStringExtra("company_name");
        String roundStatus = intent.getStringExtra("round_status");
        long jobCallId = intent.getLongExtra("job_call_id", -1);
        String recruiter = intent.getStringExtra("recruiter_name");
        String callState = intent.getStringExtra("call_state"); // "Incoming call" / "In call"

        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            removeOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }

        showOverlay(phoneNumber, company, roundStatus, recruiter, jobCallId, callState);
        return START_NOT_STICKY;
    }

    private void showOverlay(String phoneNumber, String company, String roundStatus,
                              String recruiter, long jobCallId, String callState) {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            stopSelf();
            return;
        }

        removeOverlay();

        android.view.ContextThemeWrapper themeWrapper =
                new android.view.ContextThemeWrapper(this, R.style.Theme_CallSaver);
        overlayView = LayoutInflater.from(themeWrapper).inflate(R.layout.layout_caller_overlay, null);

        String contactName = TrackerFragment.getContactNameByNumber(this, phoneNumber);
        String title;
        if (notEmpty(recruiter) && notEmpty(company)) {
            title = recruiter.trim() + " @ " + company.trim();
        } else if (notEmpty(company)) {
            title = company.trim();
        } else if (notEmpty(recruiter)) {
            title = recruiter.trim();
        } else if (notEmpty(contactName)) {
            title = contactName.trim();
        } else {
            title = phoneNumber;
        }

        TextView tvName = overlayView.findViewById(R.id.tv_overlay_caller_name);
        TextView tvStatus = overlayView.findViewById(R.id.tv_overlay_caller_status);
        TextView tvAvatar = overlayView.findViewById(R.id.tv_overlay_avatar_letter);
        View btnLog = overlayView.findViewById(R.id.btn_overlay_view_log);
        View btnClose = overlayView.findViewById(R.id.btn_overlay_close);

        if (tvName != null) tvName.setText(title);
        if (tvStatus != null) {
            String status = notEmpty(callState) ? callState : "Call";
            if (notEmpty(roundStatus)) status += "  •  " + roundStatus;
            status += "  •  " + phoneNumber;
            tvStatus.setText(status);
        }
        if (tvAvatar != null) {
            tvAvatar.setText(notEmpty(title) ? String.valueOf(title.charAt(0)).toUpperCase() : "?");
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
                stopSelf();
            });
        }
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                removeOverlay();
                stopSelf();
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
        params.y = 80;
        params.x = 0;

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            e.printStackTrace();
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
    }

    private void showForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Caller ID banner", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the floating caller-ID banner running during a call.");
            nm.createNotificationChannel(channel);
        }

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        Intent dismissIntent = new Intent(this, CallerIdBannerService.class).setAction(ACTION_DISMISS);
        PendingIntent dismissPendingIntent = PendingIntent.getService(this, 0, dismissIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("CallSaver caller ID banner active")
                .setContentText("Tap to dismiss")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(dismissPendingIntent)
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

    private boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
    }
}

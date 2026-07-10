package com.example.callsaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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

import java.util.List;

public class CallerIdService extends Service {

    private static final int NOTIFICATION_ID = 5001;
    private static final String CHANNEL_ID = "caller_id_overlay_channel";

    private WindowManager windowManager;
    private View overlayView;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Run as a foreground service immediately to avoid Android 8+ background crashes
        showForegroundNotification();

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String phoneNumber = intent.getStringExtra("phone_number");
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        DatabaseHelper db = new DatabaseHelper(this);
        JobCall call = db.getJobCallByNumber(this, phoneNumber);

        if (call == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Remove previous overlay if exists
        removeOverlay();

        // Inflate custom Caller ID overlay card
        overlayView = LayoutInflater.from(this).inflate(R.layout.layout_caller_overlay, null);

        // Bind views
        TextView tvCallerName = overlayView.findViewById(R.id.tv_overlay_caller_name);
        TextView tvCallerStatus = overlayView.findViewById(R.id.tv_overlay_caller_status);
        TextView tvNotesTimeline = overlayView.findViewById(R.id.tv_overlay_notes);
        TextView tvAvatarLetter = overlayView.findViewById(R.id.tv_overlay_avatar_letter);
        ImageView btnClose = overlayView.findViewById(R.id.btn_overlay_close);
        androidx.cardview.widget.CardView cardAvatar = overlayView.findViewById(R.id.card_overlay_avatar);

        // Set title (Recruiter @ Company)
        String company = call.getCompanyName();
        String recruiter = call.getRecruiterName();
        String title;
        if (company != null && !company.trim().isEmpty() && recruiter != null && !recruiter.trim().isEmpty()) {
            title = recruiter.trim() + " @ " + company.trim();
        } else if (company != null && !company.trim().isEmpty()) {
            title = company.trim();
        } else if (recruiter != null && !recruiter.trim().isEmpty()) {
            title = recruiter.trim();
        } else {
            title = phoneNumber;
        }
        tvCallerName.setText(title);

        // Set Status
        String statusText = "Stage: " + (call.getRoundStatus() != null ? call.getRoundStatus() : "Screening");
        if (call.getTags() != null && !call.getTags().trim().isEmpty()) {
            statusText += " · " + call.getTags();
        }
        tvCallerStatus.setText(statusText);

        // Set Avatar Letter & Color
        String initialChar = company != null && !company.isEmpty() ? String.valueOf(company.charAt(0)).toUpperCase() : "J";
        tvAvatarLetter.setText(initialChar);
        int[] avatarColors = {0xFF6366F1, 0xFF10B981, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFFF59E0B, 0xFF14B8A6};
        int colorIndex = Math.abs(title.hashCode()) % avatarColors.length;
        if (cardAvatar != null) {
            cardAvatar.setCardBackgroundColor(avatarColors[colorIndex]);
        }

        // Fetch and format pointwise timeline notes (limit to last 5)
        List<CallNote> notes = db.getNotesForJob(call.getId());
        if (notes == null || notes.isEmpty()) {
            tvNotesTimeline.setText("No notes logged for this lead yet.");
        } else {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (CallNote note : notes) {
                if (count >= 5) break;
                count++;
                sb.append(count).append(". ").append(note.note).append("\n");
            }
            // Trim final newline
            if (sb.length() > 0) {
                sb.setLength(sb.length() - 1);
            }
            tvNotesTimeline.setText(sb.toString());
        }

        // Close action
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> stopSelf());
        }

        // Configure Window Layout parameters (Middle of screen, draw over other apps)
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.CENTER; // Center overlay in the middle of the screen like Truecaller

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void showForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Caller ID Overlay Service",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Caller ID Overlay Active")
                .setContentText("Checking call details in background...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
    }

    private void removeOverlay() {
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                e.printStackTrace();
            }
            overlayView = null;
        }
    }
}

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
import android.view.MotionEvent;
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
        String company = intent.getStringExtra("company_name");
        String roundStatus = intent.getStringExtra("round_status");
        String tags = intent.getStringExtra("tags");
        long jobCallId = intent.getLongExtra("job_call_id", -1);
        String recruiter = intent.getStringExtra("recruiter_name");

        if (company == null || company.isEmpty()) {
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

        // Wrap layout inflater context with Theme.CallSaver context so Material Components inflate without crashing
        android.view.ContextThemeWrapper themeWrapper = new android.view.ContextThemeWrapper(this, R.style.Theme_CallSaver);
        overlayView = LayoutInflater.from(themeWrapper).inflate(R.layout.layout_caller_overlay, null);

        // Bind views
        TextView tvCallerName = overlayView.findViewById(R.id.tv_overlay_caller_name);
        TextView tvCallerStatus = overlayView.findViewById(R.id.tv_overlay_caller_status);
        TextView tvNotesTimeline = overlayView.findViewById(R.id.tv_overlay_notes);
        TextView tvAvatarLetter = overlayView.findViewById(R.id.tv_overlay_avatar_letter);
        ImageView btnClose = overlayView.findViewById(R.id.btn_overlay_close);
        androidx.cardview.widget.CardView cardAvatar = overlayView.findViewById(R.id.card_overlay_avatar);

        // Set title (Recruiter @ Company)
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
        String statusText = "Stage: " + (roundStatus != null ? roundStatus : "Screening");
        if (tags != null && !tags.trim().isEmpty()) {
            statusText += " · " + tags;
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
        if (jobCallId == -1) {
            tvNotesTimeline.setText("Not saved in Tracker yet. Tap the notification after the call to save and transcribe.");
        } else {
            DatabaseHelper db = new DatabaseHelper(this);
            List<CallNote> notes = db.getNotesForJob(jobCallId);
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
        }

        // Configure Window Layout parameters (Middle of screen, draw over other apps)
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
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

        // Close action
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> stopSelf());
        }

        // Edit action
        ImageView btnEdit = overlayView.findViewById(R.id.btn_overlay_edit);
        if (btnEdit != null) {
            btnEdit.setOnClickListener(v -> {
                Intent editIntent = new Intent(this, SaveContactActivity.class);
                editIntent.putExtra("phone_number", phoneNumber);
                editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(editIntent);
                stopSelf();
            });
        }

        // Touch & Swipe gesture to drag and dismiss
        View cardRoot = overlayView.findViewById(R.id.card_overlay_root);
        if (cardRoot != null) {
            cardRoot.setOnTouchListener(new View.OnTouchListener() {
                private int initialX;
                private int initialY;
                private float initialTouchX;
                private float initialTouchY;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = params.x;
                            initialY = params.y;
                            initialTouchX = event.getRawX();
                            initialTouchY = event.getRawY();
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - initialTouchX;
                            float dy = event.getRawY() - initialTouchY;
                            params.x = initialX + (int) dx;
                            params.y = initialY + (int) dy;
                            
                            // Visual feedback: fade opacity during drag
                            float dist = (float) Math.sqrt(dx * dx + dy * dy);
                            v.setAlpha(Math.max(0.3f, 1.0f - (dist / 500f)));
                            
                            try {
                                windowManager.updateViewLayout(overlayView, params);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            float deltaX = event.getRawX() - initialTouchX;
                            float deltaY = event.getRawY() - initialTouchY;
                            float totalDist = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                            
                            // Trigger dismiss if swiped past threshold (180 pixels in any direction)
                            if (totalDist > 180f) {
                                stopSelf();
                            } else {
                                // Snap back to center
                                params.x = 0;
                                params.y = 0;
                                v.setAlpha(1.0f);
                                try {
                                    windowManager.updateViewLayout(overlayView, params);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                            return true;
                    }
                    return false;
                }
            });
        }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
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

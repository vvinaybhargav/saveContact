package com.example.callsaver;

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

public class CallerIdService extends Service {

    private WindowManager windowManager;
    private View overlayView;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String companyName = intent.getStringExtra("company_name");
        String roundStatus = intent.getStringExtra("round_status");
        String tags = intent.getStringExtra("tags");

        if (companyName == null || companyName.isEmpty()) {
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

        // Inflate custom Caller ID banner layout
        overlayView = LayoutInflater.from(this).inflate(R.layout.layout_caller_id_banner, null);

        // Bind data
        TextView tvCallerName = overlayView.findViewById(R.id.tv_caller_name);
        TextView tvCallerStatus = overlayView.findViewById(R.id.tv_caller_status);
        ImageView btnCloseBanner = overlayView.findViewById(R.id.btn_close_banner);

        tvCallerName.setText(companyName);
        String statusText = "Stage: " + roundStatus;
        if (tags != null && !tags.trim().isEmpty()) {
            statusText += " (" + tags + ")";
        }
        tvCallerStatus.setText(statusText);

        // Close action
        if (btnCloseBanner != null) {
            btnCloseBanner.setOnClickListener(v -> stopSelf());
        }

        // Configure Window Layout parameters
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

        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 150; // Position below status bar

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }

        return START_NOT_STICKY;
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

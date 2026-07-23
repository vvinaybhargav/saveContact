package com.example.callsaver;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

public class CallActionReceiver extends BroadcastReceiver {

    private static final String TAG = "CallActionReceiver";
    public static final String ACTION_QUICK_SAVE = "com.example.callsaver.action.QUICK_SAVE_TRANSCRIBE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();

        if (ACTION_QUICK_SAVE.equals(action)) {
            String phoneNumber = intent.getStringExtra("phone_number");
            int duration = intent.getIntExtra("duration", 0);
            long timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis());

            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                // Cancel post-call dialog alert notification
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(phoneNumber.hashCode());
                }

                // Handle quick save asynchronously
                final PendingResult pendingResult = goAsync();
                new Thread(() -> {
                    try {
                        handleBackgroundQuickSave(context, phoneNumber, duration, timestamp);
                    } catch (Exception e) {
                        Log.e(TAG, "Quick save failed: " + e.getMessage());
                    } finally {
                        pendingResult.finish();
                    }
                }).start();
            }
        }
    }

    private void handleBackgroundQuickSave(Context context, String phoneNumber, int duration, long timestamp) {
        saveFallbackLead(context, phoneNumber, duration, timestamp, "");
        postStatusNotification(context, phoneNumber, "✅ Call Saved in Tracker", "Logged call in CallSaver.");
        Intent saveIntent = new Intent(context, SaveContactActivity.class);
        saveIntent.putExtra("phone_number", phoneNumber);
        saveIntent.putExtra("call_timestamp", timestamp);
        saveIntent.putExtra("call_duration", duration);
        saveIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(saveIntent);
    }

    private void saveFallbackLead(Context context, String phoneNumber, int duration, long timestamp, String notes) {
        try {
            DatabaseHelper db = new DatabaseHelper(context);
            JobCall existing = db.getJobCallByNumber(context, phoneNumber);
            if (existing != null) {
                db.insertNote(existing.getId(), notes, System.currentTimeMillis());
            } else {
                JobCall newCall = new JobCall(phoneNumber, "", "Screening", "Auto-Saved", notes, duration, timestamp);
                long newId = db.insertJobCall(newCall);
                if (newId > 0) {
                    db.insertNote(newId, notes, System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed fallback save: " + e.getMessage());
        }
    }

    private void postStatusNotification(Context context, String phoneNumber, String title, String content) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String channelId = "call_saver_notif_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Call Logs & Transcriptions", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();

        nm.notify(phoneNumber.hashCode() + 200, notification);
    }
}

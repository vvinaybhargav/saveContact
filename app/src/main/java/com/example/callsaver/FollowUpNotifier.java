package com.example.callsaver;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.List;

/**
 * On app open, checks for interviews whose scheduled call time has passed without a
 * status update and nudges the user with a single grouped notification. Dedupes per
 * job id + schedule string so the same reminder doesn't fire repeatedly.
 */
public class FollowUpNotifier {

    private static final String CHANNEL_ID = "follow_up_reminder_channel";
    private static final int NOTIFICATION_ID = 7001;
    private static final String PREFS_NAME = "CallSaverPrefs";
    private static final String PREF_KEY = "notified_followups";

    public static void checkAndNotify(Context context) {
        DatabaseHelper dbHelper = new DatabaseHelper(context);
        List<JobCall> allCalls = dbHelper.getAllJobCalls();

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        java.util.Set<String> notified = new java.util.HashSet<>(
                prefs.getStringSet(PREF_KEY, new java.util.HashSet<>()));

        StringBuilder companies = new StringBuilder();
        int count = 0;
        java.util.Set<String> stillPending = new java.util.HashSet<>();

        for (JobCall call : allCalls) {
            if (!FollowUpUtils.needsFollowUp(call)) continue;
            String dedupeKey = call.getId() + "|" + call.getTentativeSchedule();
            stillPending.add(dedupeKey);
            if (notified.contains(dedupeKey)) continue;
            count++;
            if (companies.length() > 0) companies.append(", ");
            companies.append(call.getCompanyName() != null && !call.getCompanyName().isEmpty()
                    ? call.getCompanyName() : "Unknown");
        }

        // Drop stale dedupe keys for calls that are no longer pending (e.g. schedule changed or status updated).
        notified.retainAll(stillPending);
        notified.addAll(stillPending);
        prefs.edit().putStringSet(PREF_KEY, notified).apply();

        if (count == 0) return;

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Interview follow-up reminders", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Reminds you to log an update after a scheduled interview time has passed.");
            nm.createNotificationChannel(channel);
        }

        Intent tapIntent = new Intent(context, MainActivity.class);
        tapIntent.putExtra("open_tab", "upcoming");
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, tapIntent, piFlags);

        String title = count == 1 ? "Interview update needed" : count + " interview updates needed";
        String content = "Call time passed for: " + companies + ". Log what happened.";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        nm.notify(NOTIFICATION_ID, builder.build());
    }
}

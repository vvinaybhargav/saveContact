package com.example.callsaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class CallReceiver extends BroadcastReceiver {

    private static final String TAG = "CallReceiver";
    private static final String PREFS_NAME = "CallSaverPrefs";
    private static final String KEY_LAST_STATE = "last_state";
    private static final String KEY_INCOMING_NUMBER = "incoming_number";
    private static final String KEY_ANSWERED = "answered";
    private static final String CHANNEL_ID = "recruiter_save_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }

        String stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (stateStr == null) {
            return;
        }

        Log.d(TAG, "Phone State Changed: " + stateStr);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastSavedState = prefs.getString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_IDLE);

        if (stateStr.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
            // Incoming call ringing - reset the "answered" flag for this new call.
            String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_RINGING)
                    .putBoolean(KEY_ANSWERED, false);
            if (incomingNumber != null && !incomingNumber.isEmpty()) {
                // The number can arrive on a later RINGING broadcast; store it whenever present.
                editor.putString(KEY_INCOMING_NUMBER, incomingNumber);
                Log.d(TAG, "Incoming call detected from number: " + incomingNumber);

                // Show the caller-ID overlay banner if this is a tracked recruiter.
                DatabaseHelper dbHelper = new DatabaseHelper(context);
                JobCall jobCall = dbHelper.getJobCallByNumber(context, incomingNumber);
                if (jobCall != null) {
                    Log.d(TAG, "Tracked job caller detected! Starting Caller ID Overlay service.");
                    Intent serviceIntent = new Intent(context, CallerIdService.class);
                    serviceIntent.putExtra("company_name", jobCall.getCompanyName());
                    serviceIntent.putExtra("round_status", jobCall.getRoundStatus());
                    serviceIntent.putExtra("tags", jobCall.getTags());
                    context.startService(serviceIntent);
                }
            }
            editor.apply();
        } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
            // OFFHOOK after RINGING == the incoming call was answered.
            // OFFHOOK straight from IDLE == an outgoing call (never marked answered).
            boolean answeredIncoming = TelephonyManager.EXTRA_STATE_RINGING.equals(lastSavedState)
                    || prefs.getBoolean(KEY_ANSWERED, false);
            prefs.edit()
                    .putString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
                    .putBoolean(KEY_ANSWERED, answeredIncoming)
                    .apply();
            Log.d(TAG, "Call active (OFFHOOK). Answered incoming: " + answeredIncoming);
            context.stopService(new Intent(context, CallerIdService.class));
        } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
            // Stop Caller ID banner
            context.stopService(new Intent(context, CallerIdService.class));

            String incomingNumber = prefs.getString(KEY_INCOMING_NUMBER, null);
            boolean answered = prefs.getBoolean(KEY_ANSWERED, false);
            Log.d(TAG, "Call ended (IDLE). Number: " + incomingNumber + ", answered: " + answered);

            // Only notify for an ANSWERED INCOMING call whose number is NOT already in contacts.
            if (answered && incomingNumber != null && !incomingNumber.trim().isEmpty()) {
                if (!isContactExists(context, incomingNumber)) {
                    int duration = getLastCallDuration(context, incomingNumber);
                    Log.d(TAG, "Number not in contacts -> posting save notification. Duration: " + duration + "s");
                    showSaveNotification(context, incomingNumber, duration);
                } else {
                    Log.d(TAG, "Number already exists in contacts. No notification.");
                }
            }

            // Clean up state
            prefs.edit()
                    .remove(KEY_INCOMING_NUMBER)
                    .remove(KEY_ANSWERED)
                    .putString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_IDLE)
                    .apply();
        }
    }

    /**
     * Posts a heads-up notification prompting the user to save an unknown caller.
     * Tapping it opens {@link SaveContactActivity} (a user-initiated launch, so it is
     * not blocked by background-activity-launch restrictions like the old auto-popup was).
     */
    private void showSaveNotification(Context context, String number, int duration) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Save recruiter contacts",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Prompts you to save unknown callers to your Job Tracker.");
            nm.createNotificationChannel(channel);
        }

        Intent tapIntent = new Intent(context, SaveContactActivity.class);
        tapIntent.putExtra("phone_number", number);
        tapIntent.putExtra("timestamp", System.currentTimeMillis());
        tapIntent.putExtra("duration", duration);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, number.hashCode(), tapIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("Save this caller?")
                .setContentText(number + " isn't in your contacts. Tap to log it.")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(number + " isn't saved in your contacts. Tap to add it to your Job Tracker & phone contacts."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();

        try {
            nm.notify(number.hashCode(), notification);
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted (Android 13+)
            Log.e(TAG, "Cannot post notification: " + e.getMessage());
        }
    }

    /**
     * Queries the Android Contacts ContentProvider to see if the given phone number exists.
     */
    private boolean isContactExists(Context context, String number) {
        if (number == null || number.isEmpty()) {
            return false;
        }

        try {
            Uri lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number)
            );
            String[] projection = { ContactsContract.PhoneLookup._ID };

            try (Cursor cursor = context.getContentResolver().query(lookupUri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException checking contacts: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception checking contacts: " + e.getMessage());
        }
        return false;
    }

    /**
     * Queries the system's CallLog provider for the duration of the last call matching the number.
     */
    private int getLastCallDuration(Context context, String number) {
        if (number == null || number.isEmpty()) {
            return 0;
        }
        try {
            Uri callUri = android.provider.CallLog.Calls.CONTENT_URI;
            String[] projection = { android.provider.CallLog.Calls.DURATION };
            String selection = android.provider.CallLog.Calls.NUMBER + " = ?";
            String[] selectionArgs = { number };
            String sortOrder = android.provider.CallLog.Calls.DATE + " DESC LIMIT 1";
            try (Cursor cursor = context.getContentResolver().query(callUri, projection, selection, selectionArgs, sortOrder)) {
                if (cursor != null && cursor.moveToFirst()) {
                    return cursor.getInt(0); // Returns duration in seconds
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException querying call duration: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception querying call duration: " + e.getMessage());
        }
        return 0;
    }
}

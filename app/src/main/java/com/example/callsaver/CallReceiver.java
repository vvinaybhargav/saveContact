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
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

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
        if (intent == null) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastSavedState = prefs.getString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_IDLE);

        if (Intent.ACTION_NEW_OUTGOING_CALL.equals(intent.getAction())) {
            String outgoingNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            if (outgoingNumber != null && !outgoingNumber.trim().isEmpty()) {
                prefs.edit()
                        .putString(KEY_INCOMING_NUMBER, outgoingNumber)
                        .putBoolean(KEY_ANSWERED, true)
                        .putString(KEY_LAST_STATE, "OUTGOING")
                        .apply();
                Log.d(TAG, "Outgoing call detected to: " + outgoingNumber);
                Toast.makeText(context, "CallSaver Diagnostic: Outgoing to " + outgoingNumber, Toast.LENGTH_LONG).show();

                // Show overlay banner if number is tracked
                DatabaseHelper db = new DatabaseHelper(context);
                JobCall call = db.getJobCallByNumber(context, outgoingNumber);
                if (call != null) {
                    Toast.makeText(context, "Match found for " + call.getCompanyName() + "! Showing overlay...", Toast.LENGTH_SHORT).show();
                    Intent overlayIntent = new Intent(context, CallerIdService.class);
                    overlayIntent.putExtra("phone_number", outgoingNumber);
                    overlayIntent.putExtra("company_name", call.getCompanyName());
                    overlayIntent.putExtra("round_status", call.getRoundStatus());
                    overlayIntent.putExtra("tags", call.getTags());
                    overlayIntent.putExtra("job_call_id", (long) call.getId());
                    overlayIntent.putExtra("recruiter_name", call.getRecruiterName());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(overlayIntent);
                    } else {
                        context.startService(overlayIntent);
                    }
                } else {
                    Toast.makeText(context, "No match in tracker for outgoing number", Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }

        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }

        String stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (stateStr == null) {
            return;
        }

        Log.d(TAG, "Phone State Changed: " + stateStr);

        if (stateStr.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
            String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_RINGING)
                    .putBoolean(KEY_ANSWERED, false);
            if (incomingNumber != null && !incomingNumber.isEmpty()) {
                editor.putString(KEY_INCOMING_NUMBER, incomingNumber);
                Log.d(TAG, "Incoming call detected from number: " + incomingNumber);
                Toast.makeText(context, "CallSaver Diagnostic: Incoming from " + incomingNumber, Toast.LENGTH_LONG).show();

                // Show overlay banner if number is tracked
                DatabaseHelper db = new DatabaseHelper(context);
                JobCall call = db.getJobCallByNumber(context, incomingNumber);
                if (call != null) {
                    Toast.makeText(context, "Match found for " + call.getCompanyName() + "! Showing overlay...", Toast.LENGTH_SHORT).show();
                    Intent overlayIntent = new Intent(context, CallerIdService.class);
                    overlayIntent.putExtra("phone_number", incomingNumber);
                    overlayIntent.putExtra("company_name", call.getCompanyName());
                    overlayIntent.putExtra("round_status", call.getRoundStatus());
                    overlayIntent.putExtra("tags", call.getTags());
                    overlayIntent.putExtra("job_call_id", (long) call.getId());
                    overlayIntent.putExtra("recruiter_name", call.getRecruiterName());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(overlayIntent);
                    } else {
                        context.startService(overlayIntent);
                    }
                } else {
                    Toast.makeText(context, "No match in tracker for incoming number", Toast.LENGTH_SHORT).show();
                }
            }
            editor.apply();
        } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
            boolean answeredIncoming = TelephonyManager.EXTRA_STATE_RINGING.equals(lastSavedState)
                    || prefs.getBoolean(KEY_ANSWERED, false);
            prefs.edit()
                    .putString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
                    .putBoolean(KEY_ANSWERED, answeredIncoming)
                    .apply();
            Log.d(TAG, "Call active (OFFHOOK). Answered incoming: " + answeredIncoming);
        } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
            // Stop Caller ID banner
            context.stopService(new Intent(context, CallerIdService.class));

            String incomingNumber = prefs.getString(KEY_INCOMING_NUMBER, null);
            boolean answered = prefs.getBoolean(KEY_ANSWERED, false);
            Log.d(TAG, "Call ended (IDLE). Number: " + incomingNumber + ", answered: " + answered);

            // Log call history duration for tracked numbers
            if (incomingNumber != null && !incomingNumber.trim().isEmpty()) {
                DatabaseHelper db = new DatabaseHelper(context);
                JobCall call = db.getJobCallByNumber(context, incomingNumber);
                int duration = getLastCallDuration(context, incomingNumber);
                
                if (call != null) {
                    // Log call history to database
                    String typeLabel = "Incoming";
                    if ("OUTGOING".equals(lastSavedState)) {
                        typeLabel = "Outgoing";
                    } else if (!answered) {
                        typeLabel = "Missed";
                    }
                    db.insertCallHistory(call.getId(), typeLabel, duration, System.currentTimeMillis());
                }

                // If answered (or outgoing), post notification to log/transcribe
                if (answered || "OUTGOING".equals(lastSavedState)) {
                    Log.d(TAG, "Posting save/transcribe notification. Duration: " + duration + "s");
                    showSaveNotification(context, incomingNumber, duration);
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

        DatabaseHelper db = new DatabaseHelper(context);
        JobCall call = db.getJobCallByNumber(context, number);
        
        String title = "Save call to Tracker?";
        String content = "Number: " + number + " isn't logged. Tap to log & transcribe.";
        if (call != null) {
            String label = (call.getCompanyName() != null && !call.getCompanyName().isEmpty()) ? call.getCompanyName() : number;
            title = "Transcribe call for " + label;
            content = "Tap to review call recording and transcribe notes.";
        }

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();

        try {
            nm.notify(number.hashCode(), notification);
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot post notification: " + e.getMessage());
        }
    }

    private boolean isDefaultDialer(Context context) {
        try {
            TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            return tm != null && context.getPackageName().equals(tm.getDefaultDialerPackage());
        } catch (Exception e) {
            return false;
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

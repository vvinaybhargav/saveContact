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

                // Show overlay banner for all calls
                DatabaseHelper db = new DatabaseHelper(context);
                JobCall call = db.getJobCallByNumber(context, outgoingNumber);
                Intent overlayIntent = new Intent(context, CallerIdService.class);
                overlayIntent.putExtra("phone_number", outgoingNumber);
                if (call != null) {
                    Toast.makeText(context, "Match found for " + call.getCompanyName() + "! Showing overlay...", Toast.LENGTH_SHORT).show();
                    overlayIntent.putExtra("company_name", call.getCompanyName());
                    overlayIntent.putExtra("round_status", call.getRoundStatus());
                    overlayIntent.putExtra("tags", call.getTags());
                    overlayIntent.putExtra("job_call_id", (long) call.getId());
                    overlayIntent.putExtra("recruiter_name", call.getRecruiterName());
                } else {
                    Toast.makeText(context, "Unsaved number: Showing overlay...", Toast.LENGTH_SHORT).show();
                    overlayIntent.putExtra("company_name", "Unknown Recruiter");
                    overlayIntent.putExtra("round_status", "Not Saved");
                    overlayIntent.putExtra("tags", "");
                    overlayIntent.putExtra("job_call_id", -1L);
                    overlayIntent.putExtra("recruiter_name", "");
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(overlayIntent);
                } else {
                    context.startService(overlayIntent);
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
        DebugLogger.log(context, "[Receiver] State changed to: " + stateStr);

        if (stateStr.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
            String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_RINGING)
                    .putBoolean(KEY_ANSWERED, false);
            if (incomingNumber != null && !incomingNumber.isEmpty()) {
                editor.putString(KEY_INCOMING_NUMBER, incomingNumber);
                Log.d(TAG, "Incoming call detected from number: " + incomingNumber);
                DebugLogger.log(context, "[Receiver] Incoming call number: " + incomingNumber);
                Toast.makeText(context, "CallSaver Diagnostic: Incoming from " + incomingNumber, Toast.LENGTH_LONG).show();

                // Show overlay banner for all calls
                DatabaseHelper db = new DatabaseHelper(context);
                JobCall call = db.getJobCallByNumber(context, incomingNumber);
                Intent overlayIntent = new Intent(context, CallerIdService.class);
                overlayIntent.putExtra("phone_number", incomingNumber);
                if (call != null) {
                    Toast.makeText(context, "Match found for " + call.getCompanyName() + "! Showing overlay...", Toast.LENGTH_SHORT).show();
                    overlayIntent.putExtra("company_name", call.getCompanyName());
                    overlayIntent.putExtra("round_status", call.getRoundStatus());
                    overlayIntent.putExtra("tags", call.getTags());
                    overlayIntent.putExtra("job_call_id", (long) call.getId());
                    overlayIntent.putExtra("recruiter_name", call.getRecruiterName());
                } else {
                    Toast.makeText(context, "Unsaved number: Showing overlay...", Toast.LENGTH_SHORT).show();
                    overlayIntent.putExtra("company_name", "Unknown Recruiter");
                    overlayIntent.putExtra("round_status", "Not Saved");
                    overlayIntent.putExtra("tags", "");
                    overlayIntent.putExtra("job_call_id", -1L);
                    overlayIntent.putExtra("recruiter_name", "");
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(overlayIntent);
                } else {
                    context.startService(overlayIntent);
                }
            } else {
                DebugLogger.log(context, "[Receiver] Incoming call (No Number Extra)");
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
            DebugLogger.log(context, "[Receiver] Offhook active. answeredIncoming: " + answeredIncoming);
        } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
            // Dismiss overlay banner immediately
            context.stopService(new Intent(context, CallerIdService.class));
            DebugLogger.log(context, "[Receiver] Idle transition. Dismissed overlay. Scanning Call Log in 800ms...");

            // Query call log with a brief delay to allow system write synchronization
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    CallLogEntry entry = getLatestCallLogEntry(context);
                    if (entry != null) {
                        long callEndTime = entry.date + (entry.duration * 1000L);
                        long diff = Math.abs(System.currentTimeMillis() - callEndTime);
                        DebugLogger.log(context, "[Receiver] Call log: number=" + entry.number + ", duration=" + entry.duration + "s, type=" + entry.type + ", timeDiff=" + (diff / 1000) + "s ago");
                        // Make sure the call log was written in the last 15 seconds
                        if (diff < 15000L) {
                            Log.d(TAG, "Matched call log entry: " + entry.number + ", duration: " + entry.duration);
                            
                            DatabaseHelper db = new DatabaseHelper(context);
                            JobCall call = db.getJobCallByNumber(context, entry.number);
                            
                            // Check call direction and answered status
                            boolean isOutgoing = entry.type == android.provider.CallLog.Calls.OUTGOING_TYPE;
                            boolean isIncomingAnswered = entry.type == android.provider.CallLog.Calls.INCOMING_TYPE && entry.duration > 0;
                            
                            if (call != null) {
                                String typeLabel = "Incoming";
                                if (isOutgoing) {
                                    typeLabel = "Outgoing";
                                } else if (entry.type == android.provider.CallLog.Calls.MISSED_TYPE || entry.type == android.provider.CallLog.Calls.REJECTED_TYPE) {
                                    typeLabel = "Missed";
                                }
                                db.insertCallHistory(call.getId(), typeLabel, entry.duration, entry.date);
                            }
                            
                            if (isOutgoing || isIncomingAnswered) {
                                DebugLogger.log(context, "[Receiver] Launching SaveContactActivity popup for " + entry.number);
                                Intent dialogIntent = new Intent(context, SaveContactActivity.class);
                                dialogIntent.putExtra("phone_number", entry.number);
                                dialogIntent.putExtra("duration", entry.duration);
                                dialogIntent.putExtra("timestamp", entry.date);
                                dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                context.startActivity(dialogIntent);
                            } else {
                                DebugLogger.log(context, "[Receiver] Call not answered or not outgoing (Skipped popup)");
                            }
                        } else {
                            Log.d(TAG, "Latest call log is too old: diff = " + diff / 1000 + "s");
                            DebugLogger.log(context, "[Receiver] Latest call log is too old (diff = " + (diff / 1000) + "s)");
                        }
                    } else {
                        Log.d(TAG, "No call log entry found");
                        DebugLogger.log(context, "[Receiver] No call log entries found on device.");
                    }
                }
            }, 800);

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

        // Quick Action Pending Intent
        Intent quickActionIntent = new Intent(context, CallActionReceiver.class);
        quickActionIntent.setAction("com.example.callsaver.action.QUICK_SAVE_TRANSCRIBE");
        quickActionIntent.putExtra("phone_number", number);
        quickActionIntent.putExtra("duration", duration);
        quickActionIntent.putExtra("timestamp", System.currentTimeMillis());

        PendingIntent quickActionPendingIntent = PendingIntent.getBroadcast(
                context, number.hashCode() + 100, quickActionIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_save, "Quick Save & Transcribe", quickActionPendingIntent)
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
            String sortOrder = android.provider.CallLog.Calls.DATE + " DESC";
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

    private static CallLogEntry getLatestCallLogEntry(Context context) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            DebugLogger.log(context, "[Receiver] Call log query skipped: READ_CALL_LOG permission NOT granted.");
            return null;
        }
        
        android.database.Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    new String[] {
                            android.provider.CallLog.Calls.NUMBER,
                            android.provider.CallLog.Calls.DATE,
                            android.provider.CallLog.Calls.DURATION,
                            android.provider.CallLog.Calls.TYPE
                    },
                    null,
                    null,
                    android.provider.CallLog.Calls.DATE + " DESC"
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                String number = cursor.getString(0);
                long date = cursor.getLong(1);
                int duration = cursor.getInt(2);
                int type = cursor.getInt(3);
                return new CallLogEntry(number, date, duration, type);
            } else {
                DebugLogger.log(context, "[Receiver] Call log query returned empty cursor.");
            }
        } catch (SecurityException se) {
            DebugLogger.log(context, "[Receiver] Call log query failed with SecurityException: " + se.getMessage());
        } catch (Exception e) {
            DebugLogger.log(context, "[Receiver] Call log query failed with Exception: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }
    
    private static class CallLogEntry {
        String number;
        long date;
        int duration;
        int type;
        
        CallLogEntry(String number, long date, int duration, int type) {
            this.number = number;
            this.date = date;
            this.duration = duration;
            this.type = type;
        }
    }
}

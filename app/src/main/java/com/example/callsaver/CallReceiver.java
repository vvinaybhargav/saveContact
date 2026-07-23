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

import java.io.File;
import java.util.List;
import java.util.ArrayList;

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
            DebugLogger.log(context, "[Receiver] Idle transition. Dismissed overlay. Scanning Call Log in background...");

            final String incomingNumber = prefs.getString(KEY_INCOMING_NUMBER, null);

            // Clean up state
            prefs.edit()
                    .remove(KEY_INCOMING_NUMBER)
                    .remove(KEY_ANSWERED)
                    .putString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_IDLE)
                    .apply();

            // Run recent call logs checking on background thread
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(800); // Wait 800ms for system write sync
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    processRecentCalls(context, 1, incomingNumber);
                }
            }).start();
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
                .addAction(android.R.drawable.ic_menu_save, "Quick Save & Note", quickActionPendingIntent)
                .build();

        try {
            nm.notify(number.hashCode(), notification);
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot post notification: " + e.getMessage());
        }
    }

    /**
     * Tells the user WHY the after-call AI processing didn't save anything (missing
     * recording, transcription failure, OpenAI error, DB error, etc.) instead of
     * silently doing nothing or showing a generic "tap to save" prompt. Tapping still
     * opens the manual save/transcribe flow so they can fix it themselves. No calendar
     * action here - purely informational.
     */
    private void showAiFailureNotification(Context context, String number, int duration, String reason) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

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
                context, number.hashCode() + 300, tapIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠ Call not auto-logged for " + number)
                .setContentText(reason)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(reason))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();

        try {
            nm.notify(number.hashCode() + 300, notification);
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot post AI-failure notification: " + e.getMessage());
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

    private static List<CallLogEntry> getRecentCallLogEntries(Context context, int limit) {
        List<CallLogEntry> entries = new ArrayList<>();
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            DebugLogger.log(context, "[Receiver] Call log query skipped: READ_CALL_LOG permission NOT granted.");
            return entries;
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
                do {
                    String number = cursor.getString(0);
                    long date = cursor.getLong(1);
                    int duration = cursor.getInt(2);
                    int type = cursor.getInt(3);
                    entries.add(new CallLogEntry(number, date, duration, type));
                } while (cursor.moveToNext() && entries.size() < limit);
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
        return entries;
    }

    private static boolean isSignatureProcessed(Context context, String signature) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String list = prefs.getString("processed_call_signatures_list", null);
        if (list == null) {
            // First run: mark all current call logs as processed so we don't process old history
            List<CallLogEntry> currentEntries = getRecentCallLogEntries(context, 10);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < currentEntries.size(); i++) {
                String sig = currentEntries.get(i).number + "|" + currentEntries.get(i).date + "|" + currentEntries.get(i).duration;
                sb.append("[").append(sig).append("]");
                if (i < currentEntries.size() - 1) sb.append(",");
            }
            prefs.edit().putString("processed_call_signatures_list", sb.toString()).apply();
            list = sb.toString();
        }
        return list.contains("[" + signature + "]");
    }

    private static void markSignatureProcessed(Context context, String signature) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String list = prefs.getString("processed_call_signatures_list", "");
        List<String> items = new ArrayList<>();
        if (!list.isEmpty()) {
            String[] parts = list.split(",");
            for (String part : parts) {
                if (!part.isEmpty()) {
                    items.add(part);
                }
            }
        }
        String newItem = "[" + signature + "]";
        if (!items.contains(newItem)) {
            items.add(newItem);
        }
        if (items.size() > 20) {
            items = items.subList(items.size() - 20, items.size());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(items.get(i));
        }
        prefs.edit().putString("processed_call_signatures_list", sb.toString()).apply();
    }

    private void processRecentCalls(final Context context, final int attempt, final String fallbackNumber) {
        List<CallLogEntry> entries = getRecentCallLogEntries(context, 5);
        boolean foundNewCall = false;
        long now = System.currentTimeMillis();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        for (CallLogEntry entry : entries) {
            long callEndTime = entry.date + (entry.duration * 1000L);
            long diff = Math.abs(now - callEndTime);

            // Allow window of 10 minutes to scan recent call logs (handles call waiting, delays)
            if (diff < 600000L) {
                String callSignature = entry.number + "|" + entry.date + "|" + entry.duration;
                if (!isSignatureProcessed(context, callSignature)) {
                    markSignatureProcessed(context, callSignature);
                    foundNewCall = true;
                    processSingleCallEntry(context, entry, prefs);
                }
            }
        }

        if (!foundNewCall && attempt < 4) {
            DebugLogger.log(context, "[Receiver] No new recent call log found (attempt " + attempt + "/4). Retrying in 1500ms...");
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            processRecentCalls(context, attempt + 1, fallbackNumber);
        } else if (!foundNewCall) {
            DebugLogger.log(context, "[Receiver] No recent call log found after 4 attempts.");
            if (fallbackNumber != null && !fallbackNumber.trim().isEmpty()) {
                DatabaseHelper db = new DatabaseHelper(context);
                JobCall trackedCall = db.getJobCallByNumber(context, fallbackNumber);
                if (trackedCall != null) {
                    showAiFailureNotification(context, fallbackNumber, 0,
                            "The system call log took too long to update after this call ended, so it couldn't be auto-processed. Tap to save/transcribe manually.");
                }
            }
        }
    }

    private void processSingleCallEntry(Context context, CallLogEntry entry, SharedPreferences prefs) {
        DebugLogger.log(context, "[Receiver] Call log matched: number=" + entry.number + ", duration=" + entry.duration + "s, type=" + entry.type);
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
            db.insertCallHistory(call.getId(), typeLabel, entry.duration, entry.date + entry.duration * 1000L);
        }
        
        boolean autoTranscribe = prefs.getBoolean("auto_transcribe_background", true);

        if (isOutgoing || isIncomingAnswered) {
            if (autoTranscribe && entry.duration >= 15) {
                DebugLogger.log(context, "[Receiver] Auto-transcribing call for " + entry.number + " in background...");
                triggerBackgroundTranscription(context, entry.number, entry.duration, entry.date + entry.duration * 1000L);
            } else {
                DebugLogger.log(context, "[Receiver] Launching SaveContactActivity popup for " + entry.number);
                Intent dialogIntent = new Intent(context, SaveContactActivity.class);
                dialogIntent.putExtra("phone_number", entry.number);
                dialogIntent.putExtra("duration", entry.duration);
                dialogIntent.putExtra("timestamp", entry.date);
                dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(dialogIntent);
            }
        } else {
            DebugLogger.log(context, "[Receiver] Call not answered or not outgoing (Skipped popup)");
        }
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

    private void triggerBackgroundTranscription(Context context, String phoneNumber, int duration, long timestamp) {
        // Obsolete audio transcription replaced by in-call/post-call manual notes
    }

    private void showNotification(Context context, String title, String content) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build();
                
        try {
            nm.notify(9999, notification);
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot post notification: " + e.getMessage());
        }
    }

    /**
     * Final "call fully processed" notification. Tapping opens the app (Tracker tab)
     * instead of a Calendar-add screen - purely informational, no calendar action.
     */
    private void showCallProcessedNotification(Context context, String phoneNumber, String title, String content) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent tapIntent = new Intent(context, MainActivity.class);
        tapIntent.putExtra("open_tab", "tracker");
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, phoneNumber.hashCode() + 500, tapIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();

        try {
            nm.notify(phoneNumber.hashCode() + 500, notification);
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot post notification: " + e.getMessage());
        }
    }

    /**
     * Ongoing low-key notification showing which stage of the after-call pipeline is
     * currently running (looking for recording / transcribing / extracting fields),
     * so the user isn't left wondering what's happening. Reuses one notification ID
     * per phone number so each stage update replaces the last rather than stacking.
     */
    private void showProgressNotification(Context context, String phoneNumber, String stage) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Save recruiter contacts", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        // Try to look up existing company/recruiter name to show in title
        String displayLabel = phoneNumber;
        try {
            DatabaseHelper db = new DatabaseHelper(context);
            JobCall call = db.getJobCallByNumber(context, phoneNumber);
            if (call != null) {
                String rec = call.getRecruiterName() != null ? call.getRecruiterName().trim() : "";
                String comp = call.getCompanyName() != null ? call.getCompanyName().trim() : "";
                if (!rec.isEmpty() && !comp.isEmpty()) {
                    displayLabel = rec + " @ " + comp;
                } else if (!comp.isEmpty()) {
                    displayLabel = comp;
                } else if (!rec.isEmpty()) {
                    displayLabel = rec;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking db for progress notification label: " + e.getMessage());
        }

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Processing call - " + displayLabel)
                .setContentText(stage)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();

        try {
            nm.notify(phoneNumber.hashCode() + 400, notification);
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot post progress notification: " + e.getMessage());
        }
    }

    private void dismissProgressNotification(Context context, String phoneNumber) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.cancel(phoneNumber.hashCode() + 400);
        } catch (Exception ignored) {
        }
    }

    private static String optClean(org.json.JSONObject json, String key, String fallback) {
        if (json == null || json.isNull(key)) return fallback;
        String val = json.optString(key, fallback).trim();
        if (val.equalsIgnoreCase("null")) return fallback;
        String lower = val.toLowerCase();
        if (lower.equals("not mentioned") || lower.equals("not mentioned.") 
                || lower.equals("not_mentioned") || lower.equals("n/a") 
                || lower.equals("none") || lower.equals("unknown")) {
            return fallback;
        }
        return val;
    }

    private static String cleanNoteText(String rawText) {
        if (rawText == null) return "";
        String[] lines = rawText.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim().toLowerCase();
            if (trimmed.contains("interested in") || 
                trimmed.contains("is interested") || 
                trimmed.contains("of course") ||
                trimmed.contains("candidate is interested") ||
                trimmed.contains("ofcourse")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(line);
        }
        return sb.toString();
    }
}

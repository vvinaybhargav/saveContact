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
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

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
                // Call UI is now handled exclusively by CallSaverInCallService's
                // onCallAdded(), which launches the full-screen InCallActivity - no
                // separate overlay banner needed here anymore.
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
                // Call UI is now handled exclusively by CallSaverInCallService's
                // onCallAdded(), which launches the full-screen InCallActivity - no
                // separate overlay banner needed here anymore.
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
            DebugLogger.log(context, "[Receiver] Idle transition. Scanning Call Log in background...");

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

        // Same capture screen used everywhere else in the app (in-call, Tracker,
        // Upcoming, Recents), in review mode - not the old separate SaveContactActivity.
        // This is only ever called for a number that's already tracked (see the
        // trackedCall != null check at the call site), so look it up to reuse that
        // lead instead of creating a duplicate.
        JobCall existing = new DatabaseHelper(context).getJobCallByNumber(context, number);
        Intent tapIntent = new Intent(context, InCallActivity.class);
        tapIntent.putExtra("mode", "review");
        tapIntent.putExtra("phone_number", number);
        if (existing != null) {
            tapIntent.putExtra("company_name", existing.getCompanyName());
            tapIntent.putExtra("round_status", existing.getRoundStatus());
            tapIntent.putExtra("tags", existing.getTags());
            tapIntent.putExtra("job_call_id", (long) existing.getId());
            tapIntent.putExtra("recruiter_name", existing.getRecruiterName());
        } else {
            tapIntent.putExtra("job_call_id", -1L);
        }
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

        // Auto-detect newly created call recording file & transcribe via Deepgram + OpenAI summary
        if (entry.duration > 0) {
            findRecordingWithRetry(context, entry, 1);
        }
    }

    /**
     * The OEM's call-recorder app can take a few seconds to finish writing/saving the
     * recording file after the call ends, so a single immediate scan can miss a file
     * that shows up moments later. Retries a few times with a growing delay before
     * giving up and telling the user to pick the file manually.
     */
    private void findRecordingWithRetry(final Context context, final CallLogEntry entry, final int attempt) {
        java.io.File recordingFile = CallRecordingScanner.findLatestCallRecording(context);
        if (recordingFile == null && attempt < 4) {
            DebugLogger.log(context, "[Receiver] No recording file found yet (attempt " + attempt + "/4) - retrying...");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> new Thread(() -> findRecordingWithRetry(context, entry, attempt + 1)).start(),
                    2000L * attempt);
            return;
        }
        processRecordingFile(context, entry, recordingFile);
    }

    private void processRecordingFile(final Context context, final CallLogEntry entry, java.io.File recordingFile) {
        DatabaseHelper db = new DatabaseHelper(context);
        JobCall call = db.getJobCallByNumber(context, entry.number);
        if (recordingFile != null) {
                DebugLogger.log(context, "[Receiver] Found call recording file: " + recordingFile.getName() + ". Transcribing via Deepgram...");
                final long targetJobId = call != null ? call.getId() : -1;
                Transcriber.transcribeCallRecording(context, recordingFile, new Transcriber.TranscriptionCallback() {
                    @Override
                    public void onSuccess(String transcriptText) {
                        if (transcriptText == null || transcriptText.trim().isEmpty()) {
                            showAiFailureNotification(context, entry.number, entry.duration, "Call recording was silent or empty. Tap to transcribe manually.");
                            return;
                        }
                        DebugLogger.log(context, "[Receiver] Deepgram transcription success! Extracting summary via OpenAI...");

                        OpenAiClient.extractFields(context, "Call Transcript:\n" + transcriptText, new OpenAiClient.OpenAiCallback() {
                            @Override
                            public void onSuccess(org.json.JSONObject result) {
                                if (result == null) return;
                                String comp = result.optString("company_name", "").trim();
                                String role = result.optString("applied_role", "").trim();
                                String rec = result.optString("recruiter_name", "").trim();
                                org.json.JSONArray points = result.optJSONArray("key_discussion_points");

                                JobCall targetCall = targetJobId != -1 ? db.getJobCallById(targetJobId) : null;
                                if (targetCall == null) {
                                    targetCall = new JobCall();
                                    targetCall.setPhoneNumber(entry.number);
                                    targetCall.setTimestamp(System.currentTimeMillis());
                                    targetCall.setRoundStatus("Lead");
                                    if (!comp.isEmpty() && !"null".equalsIgnoreCase(comp)) targetCall.setCompanyName(comp);
                                    if (!role.isEmpty() && !"null".equalsIgnoreCase(role)) targetCall.setAppliedRole(role);
                                    if (!rec.isEmpty() && !"null".equalsIgnoreCase(rec)) targetCall.setRecruiterName(rec);
                                    long newId = db.insertJobCall(targetCall);
                                    db.linkPhoneToJob(newId, entry.number, rec);
                                    targetCall.setId((int) newId);
                                } else {
                                    if (!comp.isEmpty() && !"null".equalsIgnoreCase(comp) && (targetCall.getCompanyName() == null || targetCall.getCompanyName().isEmpty())) targetCall.setCompanyName(comp);
                                    if (!role.isEmpty() && !"null".equalsIgnoreCase(role) && (targetCall.getAppliedRole() == null || targetCall.getAppliedRole().isEmpty())) targetCall.setAppliedRole(role);
                                    if (!rec.isEmpty() && !"null".equalsIgnoreCase(rec) && (targetCall.getRecruiterName() == null || targetCall.getRecruiterName().isEmpty())) targetCall.setRecruiterName(rec);
                                    db.updateJobCall(targetCall);
                                }

                                if (points != null && points.length() > 0) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < points.length(); i++) {
                                        String pt = points.optString(i, "").trim();
                                        if (!pt.isEmpty()) {
                                            if (sb.length() > 0) sb.append("\n");
                                            sb.append("• ").append(pt);
                                        }
                                    }
                                    if (sb.length() > 0) {
                                        db.insertNote(targetCall.getId(), sb.toString(), System.currentTimeMillis(), DatabaseHelper.NOTE_SOURCE_CALL);
                                    }
                                }
                            }

                            @Override
                            public void onError(String error) {
                                DebugLogger.log(context, "[Receiver] OpenAI extraction error: " + error);
                                showAiFailureNotification(context, entry.number, entry.duration, "AI summary extraction failed: " + error + ". Tap to retry.");
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        DebugLogger.log(context, "[Receiver] Deepgram transcription error: " + error);
                        showAiFailureNotification(context, entry.number, entry.duration, "Auto transcription failed: " + error + ". Tap to pick file manually.");
                    }
                });
        } else {
            showAiFailureNotification(context, entry.number, entry.duration, "Call ended. Could not find auto-recording file — tap to select file or add notes manually.");
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

}

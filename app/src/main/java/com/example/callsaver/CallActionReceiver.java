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
        postStatusNotification(context, phoneNumber, "🎙️ Transcribing Call...", "Locating and transcribing call recording in background.");

        // Scan OnePlus/System dialer call recordings folder
        File audioFile = CallRecordingScanner.findLatestCallRecording(context, phoneNumber, timestamp);

        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            // Save empty/no-transcription lead to database
            saveFallbackLead(context, phoneNumber, duration, timestamp, "No call recording audio file located on device.");
            postStatusNotification(context, phoneNumber, "⚠️ Call Saved (No Recording)", "Logged call but no recording file was found.");
            return;
        }

        // Run Deepgram Transcription
        Transcriber.transcribeCallRecording(context, audioFile, new Transcriber.TranscriptionCallback() {
            @Override
            public void onSuccess(String rawTranscript) {
                // Check OpenAI Config
                String openAiKey = context.getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE)
                        .getString("openai_api_key", "").trim();

                if (openAiKey.isEmpty()) {
                    // Save raw transcription directly
                    saveFallbackLead(context, phoneNumber, duration, timestamp, rawTranscript);
                    postStatusNotification(context, phoneNumber, "✅ Call Saved & Logged", "Transcription saved successfully (OpenAI key missing).");
                    return;
                }

                // Query OpenAI Summarization
                OpenAiClient.extractFields(context, rawTranscript, new OpenAiClient.OpenAiCallback() {
                    @Override
                    public void onSuccess(JSONObject result) {
                        try {
                            DatabaseHelper db = new DatabaseHelper(context);
                            JobCall existing = db.getJobCallByNumber(context, phoneNumber);

                            String company = result.optString("company_name", "").trim();
                            if (company.isEmpty()) company = "Unknown Company";

                            String recruiter = result.optString("recruiter_name", "").trim();
                            if (ProfileUtils.isLikelyUserOwnName(context, recruiter)) {
                                recruiter = "";
                            }
                            String tags = "Auto-Saved";
                            String notes = "";
                            if (result.has("key_discussion_points")) {
                                JSONArray arr = result.getJSONArray("key_discussion_points");
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < arr.length(); i++) {
                                    sb.append("• ").append(arr.getString(i)).append("\n");
                                }
                                notes = sb.toString().trim();
                            }
                            if (notes.isEmpty()) {
                                notes = rawTranscript;
                            }

                            String candidate = result.optString("candidate_name", "").trim();
                            String role = result.optString("applied_role", "").trim();
                            String round = OpenAiClient.normalizeRoundStatus(
                                    result.optString("present_round", "").trim(), "Screening");
                            String schedule = result.optString("tentative_schedule", "").trim();
                            String notice = result.optString("notice_period", "").trim();
                            String agenda = result.optString("main_agenda", "").trim();
                            String nextSteps = result.optString("next_steps", "").trim();
                            String sentimentComment = result.optString("sentiment_comment", "").trim();
                            if (sentimentComment.equalsIgnoreCase("null")) sentimentComment = "";
                            if (!sentimentComment.isEmpty()) {
                                notes = "• " + sentimentComment + "\n" + notes;
                            }
                            String matchingSkills = OpenAiClient.jsonArrayToCsv(result, "matching_skills");
                            String notMatchingSkills = OpenAiClient.jsonArrayToCsv(result, "not_matching_skills");

                            if (existing != null) {
                                // Link phone to existing company and update status rounds
                                db.linkPhoneToJob(existing.getId(), phoneNumber, recruiter);
                                if (!notes.isEmpty()) {
                                    db.insertNote(existing.getId(), notes, System.currentTimeMillis());
                                }

                                // Enforce data preservation rule: only set if they were blank
                                if (existing.getCandidateName() == null || existing.getCandidateName().trim().isEmpty()) {
                                    existing.setCandidateName(candidate);
                                }
                                if (existing.getAppliedRole() == null || existing.getAppliedRole().trim().isEmpty()) {
                                    existing.setAppliedRole(role);
                                }
                                if (existing.getCompanyName() == null || existing.getCompanyName().trim().isEmpty()) {
                                    existing.setCompanyName(company);
                                }

                                if (OpenAiClient.shouldUpdateRoundStatus(existing.getRoundStatus(), round)) {
                                    existing.setRoundStatus(round);
                                }
                                if (!schedule.isEmpty()) {
                                    existing.setTentativeSchedule(schedule);
                                }
                                existing.setNoticePeriod(notice);
                                existing.setMainAgenda(agenda);
                                existing.setNextSteps(nextSteps);
                                if (!recruiter.isEmpty()) {
                                    existing.setRecruiterName(recruiter);
                                }
                                if (!matchingSkills.isEmpty() || !notMatchingSkills.isEmpty()) {
                                    existing.setMatchingSkills(SkillMatchUtils.mergeSkillListExcluding(
                                            existing.getMatchingSkills(), matchingSkills, notMatchingSkills));
                                    existing.setNotMatchingSkills(SkillMatchUtils.mergeSkillListExcluding(
                                            existing.getNotMatchingSkills(), notMatchingSkills, existing.getMatchingSkills()));
                                }
                                db.updateJobCall(existing);
                                postStatusNotification(context, phoneNumber, "✅ Lead Updated", "Updated interview details for " + existing.getCompanyName() + ".");
                            } else {
                                // Insert brand new JobCall lead
                                JobCall newCall = new JobCall(phoneNumber, company, round, tags, notes, duration, timestamp);
                                newCall.setCandidateName(candidate);
                                newCall.setAppliedRole(role);
                                newCall.setRecruiterName(recruiter);
                                newCall.setTentativeSchedule(schedule);
                                newCall.setNoticePeriod(notice);
                                newCall.setMainAgenda(agenda);
                                newCall.setNextSteps(nextSteps);
                                newCall.setMatchingSkills(matchingSkills);
                                newCall.setNotMatchingSkills(notMatchingSkills);

                                long newId = db.insertJobCall(newCall);
                                if (newId > 0 && !notes.isEmpty()) {
                                    db.insertNote(newId, notes, System.currentTimeMillis());
                                }
                                postStatusNotification(context, phoneNumber, "✅ Saved new Recruiter Lead", "Logged " + company + " (" + role + ") inside Tracker.");
                            }

                        } catch (Exception e) {
                            saveFallbackLead(context, phoneNumber, duration, timestamp, rawTranscript);
                            postStatusNotification(context, phoneNumber, "✅ Saved Call", "Saved transcription raw (AI extraction error).");
                        }
                    }

                    @Override
                    public void onError(String error) {
                        saveFallbackLead(context, phoneNumber, duration, timestamp, rawTranscript);
                        postStatusNotification(context, phoneNumber, "✅ Saved Call", "Saved transcription raw (AI server error).");
                    }
                });
            }

            @Override
            public void onError(String error) {
                saveFallbackLead(context, phoneNumber, duration, timestamp, "Transcription failed: " + error);
                postStatusNotification(context, phoneNumber, "⚠️ Save Failed", "Transcription server error: " + error);
            }
        });
    }

    private void saveFallbackLead(Context context, String phoneNumber, int duration, long timestamp, String notes) {
        try {
            DatabaseHelper db = new DatabaseHelper(context);
            JobCall existing = db.getJobCallByNumber(context, phoneNumber);
            if (existing != null) {
                db.insertNote(existing.getId(), notes, System.currentTimeMillis());
            } else {
                JobCall newCall = new JobCall(phoneNumber, "Unknown Company", "Screening", "Auto-Saved", notes, duration, timestamp);
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
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();

        nm.notify(phoneNumber.hashCode() + 200, notification);
    }
}

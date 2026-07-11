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
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;

public class CallerIdService extends Service {

    private static final int NOTIFICATION_ID = 5001;
    private static final String CHANNEL_ID = "caller_id_overlay_channel";

    private WindowManager windowManager;
    private View overlayView;
    private String phoneNumber;
    private String company;
    private String roundStatus;
    private String tags;
    private long jobCallId;
    private String recruiter;

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

        phoneNumber = intent.getStringExtra("phone_number");
        company = intent.getStringExtra("company_name");
        roundStatus = intent.getStringExtra("round_status");
        tags = intent.getStringExtra("tags");
        jobCallId = intent.getLongExtra("job_call_id", -1);
        recruiter = intent.getStringExtra("recruiter_name");

        if (phoneNumber == null || phoneNumber.isEmpty() || jobCallId == -1) {
            removeOverlay();
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

        // Resolve structured properties from database
        String candidateName = "";
        String mainAgenda = "";
        if (jobCallId != -1) {
            DatabaseHelper db = new DatabaseHelper(this);
            JobCall matchedCall = db.getJobCallByNumber(this, phoneNumber);
            if (matchedCall != null) {
                candidateName = matchedCall.getCandidateName();
                mainAgenda = matchedCall.getMainAgenda();
                roundStatus = matchedCall.getRoundStatus();
                tags = matchedCall.getTags();
                company = matchedCall.getCompanyName();
                recruiter = matchedCall.getRecruiterName();
            }
        }

        // Set title ([Candidate Name] @ [Company Name])
        String title;
        if (candidateName != null && !candidateName.trim().isEmpty() && company != null && !company.trim().isEmpty()) {
            title = candidateName.trim() + " @ " + company.trim();
        } else if (company != null && !company.trim().isEmpty() && recruiter != null && !recruiter.trim().isEmpty()) {
            title = recruiter.trim() + " @ " + company.trim();
        } else if (company != null && !company.trim().isEmpty()) {
            title = company.trim();
        } else if (candidateName != null && !candidateName.trim().isEmpty()) {
            title = candidateName.trim();
        } else if (recruiter != null && !recruiter.trim().isEmpty()) {
            title = recruiter.trim();
        } else {
            title = phoneNumber;
        }
        tvCallerName.setText(title);

        // Set Status
        String statusText = "Round: " + (roundStatus != null && !roundStatus.isEmpty() ? roundStatus : "-");
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

        // Fetch and format pointwise timeline notes (exactly 3 lines: Agenda, Latest, and Next Action)
        if (jobCallId == -1) {
            tvNotesTimeline.setText("Not saved in Tracker yet.");
        } else {
            StringBuilder sb = new StringBuilder();
            
            // Line 1: Agenda
            String agendaLine = (mainAgenda != null && !mainAgenda.trim().isEmpty()) ? mainAgenda.trim() : "No active agenda.";
            if (agendaLine.length() > 60) {
                agendaLine = agendaLine.substring(0, 57) + "...";
            }
            sb.append("• Agenda: ").append(agendaLine).append("\n");
            
            // Line 2: Latest Note
            DatabaseHelper db = new DatabaseHelper(this);
            List<CallNote> notes = db.getNotesForJob(jobCallId);
            String notesLine = "No previous notes.";
            if (notes != null && !notes.isEmpty()) {
                notesLine = notes.get(0).note.trim();
                if (notesLine.length() > 60) {
                    notesLine = notesLine.substring(0, 57) + "...";
                }
            }
            sb.append("• Latest: ").append(notesLine).append("\n");
            
            // Line 3: Next Steps
            String nextLine = "No next steps.";
            JobCall matchedCall = db.getJobCallByNumber(this, phoneNumber);
            if (matchedCall != null) {
                String ns = matchedCall.getNextSteps();
                String ts = matchedCall.getTentativeSchedule();
                if (ns != null && !ns.trim().isEmpty()) {
                    nextLine = ns.trim();
                } else if (ts != null && !ts.trim().isEmpty()) {
                    nextLine = "Next call: " + ts.trim();
                }
            }
            if (nextLine.length() > 60) {
                nextLine = nextLine.substring(0, 57) + "...";
            }
            sb.append("• Next: ").append(nextLine);
            
            tvNotesTimeline.setText(sb.toString());
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

        // Edit Action - Toggle Expandable In-Call Notes & Focus
        View llOverlayEditPanel = overlayView.findViewById(R.id.ll_overlay_edit_panel);
        EditText etOverlayNoteInput = overlayView.findViewById(R.id.et_overlay_note_input);
        Spinner spinnerOverlayRound = overlayView.findViewById(R.id.spinner_overlay_round);
        View btnOverlayCancelNote = overlayView.findViewById(R.id.btn_overlay_cancel_note);
        View btnOverlaySaveNote = overlayView.findViewById(R.id.btn_overlay_save_note);

        // Populate Spinner
        if (spinnerOverlayRound != null) {
            ArrayAdapter<String> roundAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{"Screening", "1st Round", "2nd Round", "Final Round", "HR / Salary", "Offered", "Rejected"});
            roundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerOverlayRound.setAdapter(roundAdapter);
            
            // Prefill spinner selection if round status is known
            if (roundStatus != null) {
                for (int i = 0; i < roundAdapter.getCount(); i++) {
                    if (roundAdapter.getItem(i).equalsIgnoreCase(roundStatus)) {
                        spinnerOverlayRound.setSelection(i);
                        break;
                    }
                }
            }
        }

        ImageView btnEdit = overlayView.findViewById(R.id.btn_overlay_edit);
        if (btnEdit != null && llOverlayEditPanel != null) {
            btnEdit.setOnClickListener(v -> {
                if (llOverlayEditPanel.getVisibility() == View.VISIBLE) {
                    // Collapse edit panel & clear focus
                    llOverlayEditPanel.setVisibility(View.GONE);
                    params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    try {
                        windowManager.updateViewLayout(overlayView, params);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    // Expand edit panel & request input focus
                    llOverlayEditPanel.setVisibility(View.VISIBLE);
                    params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    try {
                        windowManager.updateViewLayout(overlayView, params);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (etOverlayNoteInput != null) {
                        etOverlayNoteInput.requestFocus();
                    }
                }
            });
        }

        if (btnOverlayCancelNote != null && llOverlayEditPanel != null) {
            btnOverlayCancelNote.setOnClickListener(v -> {
                llOverlayEditPanel.setVisibility(View.GONE);
                if (etOverlayNoteInput != null) {
                    etOverlayNoteInput.setText("");
                }
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                try {
                    windowManager.updateViewLayout(overlayView, params);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        if (btnOverlaySaveNote != null && llOverlayEditPanel != null && etOverlayNoteInput != null) {
            btnOverlaySaveNote.setOnClickListener(v -> {
                String noteText = etOverlayNoteInput.getText().toString().trim();
                String selectedRound = spinnerOverlayRound != null ? spinnerOverlayRound.getSelectedItem().toString() : "Screening";

                if (!noteText.isEmpty()) {
                    DatabaseHelper db = new DatabaseHelper(this);
                    long targetJobId = jobCallId;
                    
                    // If this call isn't saved yet, auto-create a lead so notes can link to it
                    if (targetJobId == -1) {
                        JobCall newLead = new JobCall(phoneNumber, "Unknown Recruiter", selectedRound, "", noteText, 0, System.currentTimeMillis());
                        targetJobId = db.insertJobCall(newLead);
                        jobCallId = targetJobId;
                    } else {
                        // Insert note and update status for existing lead
                        db.insertNote(targetJobId, noteText, System.currentTimeMillis());
                        db.updateRoundStatus(targetJobId, selectedRound);
                        db.refreshNotesPreview(targetJobId);
                    }

                    // Refresh notes timeline on the banner dynamically
                    List<CallNote> notesList = db.getNotesForJob(targetJobId);
                    if (notesList != null && !notesList.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        int count = 0;
                        for (CallNote note : notesList) {
                            if (count >= 5) break;
                            count++;
                            sb.append(count).append(". ").append(note.note).append("\n");
                        }
                        if (sb.length() > 0) sb.setLength(sb.length() - 1);
                        tvNotesTimeline.setText(sb.toString());
                    }
                    
                    // If we created a new lead, update local stage status text as well
                    tvCallerStatus.setText("Stage: " + selectedRound);
                }

                // Collapse edit panel & clear focus
                llOverlayEditPanel.setVisibility(View.GONE);
                etOverlayNoteInput.setText("");
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                try {
                    windowManager.updateViewLayout(overlayView, params);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Toast.makeText(this, "Notes logged successfully!", Toast.LENGTH_SHORT).show();
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
                            
                            // Swipe left/right moves horizontally
                            params.x = initialX + (int) dx;
                            // Swipe up/down repositions the banner height
                            params.y = initialY + (int) dy;
                            
                            // Visual feedback: fade opacity based ONLY on horizontal swipe distance
                            float absDx = Math.abs(dx);
                            v.setAlpha(Math.max(0.3f, 1.0f - (absDx / 500f)));
                            
                            try {
                                windowManager.updateViewLayout(overlayView, params);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            float deltaX = event.getRawX() - initialTouchX;
                            float deltaY = event.getRawY() - initialTouchY;
                            
                            // Trigger dismiss only if swiped horizontally past threshold (180 pixels)
                            if (Math.abs(deltaX) > 180f) {
                                stopSelf();
                            } else {
                                // Snap back horizontally, but keep the new vertical height so user can reposition it!
                                params.x = 0;
                                params.y = initialY + (int) deltaY;
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

    private void startBackgroundTranscription(final String number, final int callDuration) {
        updateNotification("Searching for call recording...");

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            java.io.File recordingFile = CallRecordingScanner.findLatestCallRecording(this, number, callDuration);
            if (recordingFile == null) {
                recordingFile = CallRecordingScanner.findLatestCallRecording(this, number, 0);
            }

            if (recordingFile != null) {
                transcribeFile(recordingFile, number);
            } else {
                android.util.Log.d("CallerIdService", "No call recording found for: " + number);
                showFallbackNotification(number);
                stopSelf();
            }
        }, 2500);
    }

    private void transcribeFile(java.io.File file, String number) {
        updateNotification("Transcribing call recording...");

        android.content.SharedPreferences prefs = getSharedPreferences("CallSaverPrefs", MODE_PRIVATE);
        String apiKey = prefs.getString("deepgram_api_key", "");

        if (apiKey.isEmpty()) {
            android.util.Log.w("CallerIdService", "Deepgram API key is missing. Skipping auto-transcription.");
            showFallbackNotification(number);
            stopSelf();
            return;
        }

        Transcriber.transcribeCallRecording(this, file, new Transcriber.TranscriptionCallback() {
            @Override
            public void onSuccess(String text) {
                DatabaseHelper db = new DatabaseHelper(CallerIdService.this);
                JobCall call = db.getJobCallByNumber(CallerIdService.this, number);
                long targetJobId;
                if (call != null) {
                    targetJobId = call.getId();
                    db.insertNote(targetJobId, "[Auto-Transcribed Call Notes]\n" + text, System.currentTimeMillis());
                } else {
                    JobCall newLead = new JobCall(number, "Unknown Recruiter", "Screening", "", "[Auto-Transcribed Call Notes]\n" + text, 0, System.currentTimeMillis());
                    targetJobId = db.insertJobCall(newLead);
                }

                showSuccessNotification(number, text, targetJobId);
                stopSelf();
            }

            @Override
            public void onError(String error) {
                android.util.Log.e("CallerIdService", "Transcription failed: " + error);
                showFallbackNotification(number);
                stopSelf();
            }
        });
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("CallSaver Assistant")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        nm.notify(NOTIFICATION_ID, notification);
    }

    private void showSuccessNotification(String number, String transcript, long jobCallId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        String preview = transcript.length() > 60 ? transcript.substring(0, 57) + "..." : transcript;

        Intent intent = new Intent(this, SaveContactActivity.class);
        intent.putExtra("phone_number", number);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this,
                (int) System.currentTimeMillis(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Call Transcribed & Saved")
                .setContentText(preview)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        nm.notify((int) System.currentTimeMillis(), notification);
    }

    private void showFallbackNotification(String number) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent intent = new Intent(this, SaveContactActivity.class);
        intent.putExtra("phone_number", number);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this,
                (int) System.currentTimeMillis(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Call Ended")
                .setContentText("Tap to edit details or transcribe recording manually.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        nm.notify((int) System.currentTimeMillis(), notification);
    }
}

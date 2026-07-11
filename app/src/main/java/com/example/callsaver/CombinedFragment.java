package com.example.callsaver;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.appbar.AppBarLayout;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.widget.SpinnerAdapter;
import java.util.Calendar;
import org.json.JSONObject;
import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.io.File;
import java.util.Locale;

public class CombinedFragment extends Fragment implements CombinedAdapter.OnCombinedActionListener {

    private RecyclerView rvCombined;
    private View emptyStateLayout;
    private EditText etSearchCombined;

    private DatabaseHelper dbHelper;
    private CombinedAdapter adapter;
    private List<CombinedAdapter.CombinedCallModel> combinedList;
    private List<CombinedAdapter.CombinedCallModel> allCombinedList;

    private static final int REQ_CODE_SPEECH_INPUT = 1001;
    private EditText activeDialogNotesField;

    private EditText activeEtCandidateName;
    private EditText activeEtCompany;
    private EditText activeEtAppliedRole;
    private EditText activeEtTentativeSchedule;
    private EditText activeEtNoticePeriod;
    private EditText activeEtMainAgenda;
    private EditText activeEtNotes;
    private EditText activeEtNextSteps;
    private Spinner activeSpinnerRound;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_combined, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new DatabaseHelper(requireContext());

        rvCombined = view.findViewById(R.id.rv_combined);
        emptyStateLayout = view.findViewById(R.id.empty_state_layout);
        etSearchCombined = view.findViewById(R.id.et_search_combined);

        rvCombined.setLayoutManager(new LinearLayoutManager(requireContext()));
        combinedList = new ArrayList<>();
        allCombinedList = new ArrayList<>();
        adapter = new CombinedAdapter(requireContext(), combinedList, this);
        rvCombined.setAdapter(adapter);

        // Search text watcher
        if (etSearchCombined != null) {
            etSearchCombined.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterCombinedLogs(s.toString());
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        // Fade header on scroll
        AppBarLayout appBar = view.findViewById(R.id.appbar_combined);
        View header = view.findViewById(R.id.header_layout);
        if (appBar != null && header != null) {
            appBar.addOnOffsetChangedListener((bar, verticalOffset) -> {
                int h = header.getHeight();
                if (h > 0) {
                    header.setAlpha(1f - Math.min(1f, (float) Math.abs(verticalOffset) / h));
                }
            });
        }

        loadCombinedLogs();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadCombinedLogs();
    }

    private void loadCombinedLogs() {
        if (getContext() == null) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            showEmptyState();
            return;
        }

        allCombinedList.clear();

        // Get SIM mapping
        java.util.Map<String, String> simLabels = buildSimLabelMap();

        String[] projection = new String[]{
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.PHONE_ACCOUNT_ID
        };

        try (Cursor cursor = requireContext().getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC"
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER);
                int nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME);
                int typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE);
                int dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE);
                int durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION);
                int acctIdx = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID);

                int count = 0;
                do {
                    String number = cursor.getString(numberIdx);
                    String name = cursor.getString(nameIdx);
                    int type = cursor.getInt(typeIdx);
                    long date = cursor.getLong(dateIdx);
                    int duration = cursor.getInt(durationIdx);
                    String sim = null;
                    if (acctIdx >= 0 && !simLabels.isEmpty()) {
                        sim = simLabels.get(cursor.getString(acctIdx));
                    }

                    // Lookup from SQLite local recruiter logs
                    JobCall jobCall = dbHelper.getJobCallByNumber(requireContext(), number);

                    if (jobCall != null) {
                        allCombinedList.add(new CombinedAdapter.CombinedCallModel(number, name, type, date, sim, duration, jobCall));
                        count++;
                    }
                } while (cursor.moveToNext() && count < 80);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        filterCombinedLogs(etSearchCombined != null ? etSearchCombined.getText().toString() : "");
    }

    private java.util.Map<String, String> buildSimLabelMap() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        try {
            TelecomManager tm = (TelecomManager) requireContext().getSystemService(Context.TELECOM_SERVICE);
            if (tm != null) {
                List<PhoneAccountHandle> handles = tm.getCallCapablePhoneAccounts();
                if (handles != null && handles.size() > 1) {
                    for (int i = 0; i < handles.size(); i++) {
                        map.put(handles.get(i).getId(), "SIM " + (i + 1));
                    }
                }
            }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
        return map;
    }

    private void filterCombinedLogs(String query) {
        List<CombinedAdapter.CombinedCallModel> filtered = new ArrayList<>();
        for (CombinedAdapter.CombinedCallModel item : allCombinedList) {
            boolean matchesNumber = item.number != null && item.number.contains(query);
            boolean matchesName = item.name != null && item.name.toLowerCase().contains(query.toLowerCase());
            boolean matchesCompany = item.jobCall != null && item.jobCall.getCompanyName() != null &&
                    item.jobCall.getCompanyName().toLowerCase().contains(query.toLowerCase());
            boolean matchesTags = item.jobCall != null && item.jobCall.getTags() != null &&
                    item.jobCall.getTags().toLowerCase().contains(query.toLowerCase());

            if (query.isEmpty() || matchesNumber || matchesName || matchesCompany || matchesTags) {
                filtered.add(item);
            }
        }

        combinedList.clear();
        combinedList.addAll(filtered);
        adapter.notifyDataSetChanged();

        if (combinedList.isEmpty()) {
            showEmptyState();
        } else {
            emptyStateLayout.setVisibility(View.GONE);
            rvCombined.setVisibility(View.VISIBLE);
        }
    }

    private void showEmptyState() {
        emptyStateLayout.setVisibility(View.VISIBLE);
        rvCombined.setVisibility(View.GONE);
    }

    @Override
    public void onDialClick(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) return;
        Uri uri = Uri.fromParts("tel", phoneNumber, null);

        boolean canCall = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;

        if (canCall) {
            try {
                TelecomManager tm = (TelecomManager) requireContext().getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    tm.placeCall(uri, null);
                    return;
                }
            } catch (SecurityException e) {
                e.printStackTrace();
            }
            try {
                Intent call = new Intent(Intent.ACTION_CALL, uri);
                call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(call);
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onItemClick(CombinedAdapter.CombinedCallModel item) {
        // Open dialog for editing/logging the call log
        showAddEditCallDialog(item.jobCall, item.number);
    }

    private void showAddEditCallDialog(final JobCall editCall, final String phoneNumber) {
        if (getContext() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_add_call, null);
        builder.setView(dialogView);

        TextView dialogTitle = dialogView.findViewById(R.id.dialog_title);
        EditText etPhone = dialogView.findViewById(R.id.et_phone);
        EditText etCompany = dialogView.findViewById(R.id.et_company);
        EditText etRecruiter = dialogView.findViewById(R.id.et_recruiter_name);
        EditText etTags = dialogView.findViewById(R.id.et_tags);
        EditText etNotes = dialogView.findViewById(R.id.et_notes);
        LinearLayout llNotesTimeline = dialogView.findViewById(R.id.ll_notes_timeline);
        View labelNotes = dialogView.findViewById(R.id.label_notes);
        Spinner spinnerRound = dialogView.findViewById(R.id.spinner_round);

        EditText etCandidateName = dialogView.findViewById(R.id.et_candidate_name);
        EditText etAppliedRole = dialogView.findViewById(R.id.et_applied_role);
        EditText etTentativeSchedule = dialogView.findViewById(R.id.et_tentative_schedule);
        EditText etNoticePeriod = dialogView.findViewById(R.id.et_notice_period);
        EditText etMainAgenda = dialogView.findViewById(R.id.et_main_agenda);
        EditText etNextSteps = dialogView.findViewById(R.id.et_next_steps);

        activeEtCandidateName = etCandidateName;
        activeEtCompany = etCompany;
        activeEtAppliedRole = etAppliedRole;
        activeEtTentativeSchedule = etTentativeSchedule;
        activeEtNoticePeriod = etNoticePeriod;
        activeEtMainAgenda = etMainAgenda;
        activeEtNotes = etNotes;
        activeEtNextSteps = etNextSteps;
        activeSpinnerRound = spinnerRound;

        if (etTentativeSchedule != null) {
            etTentativeSchedule.setOnClickListener(v -> showDateTimePicker(etTentativeSchedule));
        }

        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_dialog_save);
        Button btnDelete = dialogView.findViewById(R.id.btn_dialog_delete);
        Button btnSaveContacts = dialogView.findViewById(R.id.btn_dialog_save_contacts);
        Button btnReminder = dialogView.findViewById(R.id.btn_dialog_reminder);

        TextInputLayout tilNotes = dialogView.findViewById(R.id.til_notes);

        activeDialogNotesField = etNotes;

        // Hide Save Contacts button (disabled as per instructions)
        btnSaveContacts.setVisibility(View.GONE);

        View tvDialogManualRecording = dialogView.findViewById(R.id.tv_dialog_manual_recording);
        if (tvDialogManualRecording != null) {
            tvDialogManualRecording.setOnClickListener(v -> showManualRecordingDialogForNotesField(etNotes));
        }

        tilNotes.setEndIconOnClickListener(v -> {
            Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault());
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak note...");
            try {
                startActivityForResult(intent, REQ_CODE_SPEECH_INPUT);
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Speech recognition is not supported on this device.", Toast.LENGTH_SHORT).show();
            }
        });

        // Bind Round spinner
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.round_statuses, android.R.layout.simple_spinner_item);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRound.setAdapter(spinnerAdapter);

        final AlertDialog alertDialog = builder.create();
        alertDialog.setOnDismissListener(dialog -> {
            activeDialogNotesField = null;
            activeEtCandidateName = null;
            activeEtCompany = null;
            activeEtAppliedRole = null;
            activeEtTentativeSchedule = null;
            activeEtNoticePeriod = null;
            activeEtMainAgenda = null;
            activeEtNotes = null;
            activeEtNextSteps = null;
            activeSpinnerRound = null;
        });

        if (editCall != null && editCall.getId() > 0) {
            dialogTitle.setText(R.string.title_edit_job_call);
            etPhone.setText(editCall.getPhoneNumber());
            etCompany.setText(editCall.getCompanyName());
            etRecruiter.setText(editCall.getRecruiterName());
            etTags.setText(editCall.getTags());
            
            etCandidateName.setText(editCall.getCandidateName());
            etAppliedRole.setText(editCall.getAppliedRole());
            etTentativeSchedule.setText(editCall.getTentativeSchedule());
            etNoticePeriod.setText(editCall.getNoticePeriod());
            etMainAgenda.setText(editCall.getMainAgenda());
            etNextSteps.setText(editCall.getNextSteps());

            populateTimeline(llNotesTimeline, labelNotes, editCall.getId());
            btnSave.setText(R.string.btn_update);
            btnDelete.setVisibility(View.VISIBLE);
            btnReminder.setVisibility(View.VISIBLE);

            if (editCall.getRoundStatus() != null) {
                int position = spinnerAdapter.getPosition(editCall.getRoundStatus());
                spinnerRound.setSelection(position >= 0 ? position : 0);
            }
        } else {
            dialogTitle.setText(R.string.title_add_job_call);
            btnSave.setText(R.string.btn_add);
            btnDelete.setVisibility(View.GONE);
            btnReminder.setVisibility(View.GONE);
            etPhone.setText(phoneNumber);
        }

        btnCancel.setOnClickListener(v -> alertDialog.dismiss());

        btnReminder.setOnClickListener(v -> {
            try {
                Intent calendarIntent = new Intent(Intent.ACTION_INSERT)
                        .setData(Uri.parse("content://com.android.calendar/events"))
                        .putExtra("title", "Follow up: " + (editCall != null ? editCall.getCompanyName() : ""))
                        .putExtra("description", "Follow-up reminder for " + (editCall != null ? editCall.getCompanyName() : "") + "\nStage: " + (editCall != null ? editCall.getRoundStatus() : "") + "\nNotes: " + (editCall != null ? editCall.getNotes() : ""))
                        .putExtra("beginTime", System.currentTimeMillis() + 3 * 24 * 60 * 60 * 1000L)
                        .putExtra("endTime", System.currentTimeMillis() + 3 * 24 * 60 * 60 * 1000L + 30 * 60 * 1000L);
                startActivity(calendarIntent);
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Could not open Calendar app", Toast.LENGTH_SHORT).show();
            }
        });

        btnDelete.setOnClickListener(v -> {
            if (editCall != null) {
                dbHelper.deleteJobCall(editCall.getId());
                Toast.makeText(requireContext(), R.string.msg_deleted, Toast.LENGTH_SHORT).show();
                loadCombinedLogs();
                alertDialog.dismiss();
            }
        });

        btnSave.setOnClickListener(v -> {
            String phone = etPhone.getText().toString().trim();
            String company = etCompany.getText().toString().trim();
            String recruiter = etRecruiter.getText().toString().trim();
            String tags = etTags.getText().toString().trim();
            String noteToAdd = etNotes.getText().toString().trim();
            String round = spinnerRound.getSelectedItem().toString();

            String candidate = etCandidateName.getText().toString().trim();
            String role = etAppliedRole.getText().toString().trim();
            String schedule = etTentativeSchedule.getText().toString().trim();
            String notice = etNoticePeriod.getText().toString().trim();
            String agenda = etMainAgenda.getText().toString().trim();
            String nextStepsVal = etNextSteps.getText().toString().trim();

            if (phone.isEmpty()) {
                Toast.makeText(requireContext(), R.string.msg_phone_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            if (editCall != null && editCall.getId() > 0) {
                editCall.setPhoneNumber(phone);
                editCall.setCompanyName(company);
                editCall.setRecruiterName(recruiter);
                editCall.setTags(tags);
                editCall.setRoundStatus(round);
                
                editCall.setCandidateName(candidate);
                editCall.setAppliedRole(role);
                editCall.setTentativeSchedule(schedule);
                editCall.setNoticePeriod(notice);
                editCall.setMainAgenda(agenda);
                editCall.setNextSteps(nextStepsVal);

                dbHelper.updateJobCall(editCall);
                dbHelper.linkPhoneToJob(editCall.getId(), phone, recruiter);
                if (!noteToAdd.isEmpty()) {
                    dbHelper.insertNote(editCall.getId(), noteToAdd, System.currentTimeMillis());
                }
                Toast.makeText(requireContext(), "Log updated!", Toast.LENGTH_SHORT).show();
            } else {
                // Deduplicate check
                JobCall existingCall = null;
                if (!company.isEmpty()) {
                    existingCall = dbHelper.getJobCallByCompany(company);
                }

                if (existingCall != null) {
                    // Link to existing company
                    dbHelper.linkPhoneToJob(existingCall.getId(), phone, recruiter);
                    if (!noteToAdd.isEmpty()) {
                        dbHelper.insertNote(existingCall.getId(), noteToAdd, System.currentTimeMillis());
                    }
                    
                    // Update existing company fields with edits
                    existingCall.setCandidateName(candidate);
                    existingCall.setAppliedRole(role);
                    existingCall.setTentativeSchedule(schedule);
                    existingCall.setNoticePeriod(notice);
                    existingCall.setMainAgenda(agenda);
                    existingCall.setNextSteps(nextStepsVal);
                    existingCall.setRoundStatus(round);
                    if (!recruiter.isEmpty()) {
                        existingCall.setRecruiterName(recruiter);
                    }
                    dbHelper.updateJobCall(existingCall);

                    Toast.makeText(requireContext(), "Linked to existing company " + existingCall.getCompanyName(), Toast.LENGTH_LONG).show();
                } else {
                    // Create new entry
                    JobCall newCall = new JobCall(phone, company, round, tags, "", 0, System.currentTimeMillis());
                    newCall.setRecruiterName(recruiter);
                    newCall.setCandidateName(candidate);
                    newCall.setAppliedRole(role);
                    newCall.setTentativeSchedule(schedule);
                    newCall.setNoticePeriod(notice);
                    newCall.setMainAgenda(agenda);
                    newCall.setKeyDiscussionPoints(noteToAdd);
                    newCall.setNextSteps(nextStepsVal);

                    long newId = dbHelper.insertJobCall(newCall);
                    if (newId != -1 && !noteToAdd.isEmpty()) {
                        dbHelper.insertNote(newId, noteToAdd, System.currentTimeMillis());
                    }
                    Toast.makeText(requireContext(), "Call logged to tracker!", Toast.LENGTH_SHORT).show();
                }
            }

            loadCombinedLogs();
            alertDialog.dismiss();
        });

        if (alertDialog.getWindow() != null) {
            alertDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        alertDialog.show();

        View parent = (View) dialogView.getParent();
        if (parent != null) {
            parent.setBackgroundResource(R.drawable.spinner_border);
        }
    }

    private static class TimelineRow {
        long ts;
        String text;
        boolean isNote;
        long noteId;
    }

    private void populateTimeline(LinearLayout container, View label, long jobId) {
        if (container == null) return;
        container.removeAllViews();

        List<TimelineRow> rows = new ArrayList<>();
        for (CallNote n : dbHelper.getNotesForJob(jobId)) {
            TimelineRow r = new TimelineRow();
            r.ts = n.timestamp;
            r.text = n.note;
            r.isNote = true;
            r.noteId = n.id;
            rows.add(r);
        }
        for (CallHistory h : dbHelper.getCallHistoryForJob(jobId)) {
            TimelineRow r = new TimelineRow();
            r.ts = h.timestamp;
            r.text = describeCall(h);
            r.isNote = false;
            rows.add(r);
        }

        if (rows.isEmpty()) {
            if (label != null) label.setVisibility(View.GONE);
            return;
        }
        if (label != null) label.setVisibility(View.VISIBLE);

        Collections.sort(rows, (a, b) -> Long.compare(b.ts, a.ts));

        LayoutInflater inflater = getLayoutInflater();
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
        for (TimelineRow r : rows) {
            View row = inflater.inflate(R.layout.item_note_row, container, false);
            ((TextView) row.findViewById(R.id.tv_note_text)).setText(r.text);
            ((TextView) row.findViewById(R.id.tv_note_time)).setText(sdf.format(new Date(r.ts)));
            View delete = row.findViewById(R.id.btn_delete_note);
            if (r.isNote) {
                delete.setOnClickListener(v -> {
                    dbHelper.deleteNote(r.noteId, jobId);
                    container.removeView(row);
                    if (container.getChildCount() == 0 && label != null) {
                        label.setVisibility(View.GONE);
                    }
                });
            } else {
                delete.setVisibility(View.GONE);
            }
            container.addView(row);
        }
    }

    private String describeCall(CallHistory h) {
        String label = h.type + " call";
        if (h.duration > 0) {
            label += " · " + String.format(Locale.getDefault(), "%d:%02d", h.duration / 60, h.duration % 60);
        }
        return label;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_SPEECH_INPUT && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty() && activeDialogNotesField != null) {
                String spokenText = result.get(0);
                String currentText = activeDialogNotesField.getText().toString();
                if (currentText.trim().isEmpty()) {
                    activeDialogNotesField.setText(spokenText);
                } else {
                    activeDialogNotesField.setText(currentText + " " + spokenText);
                }
                activeDialogNotesField.setSelection(activeDialogNotesField.getText().length());
            }
        }
    }

    private void showManualRecordingDialogForNotesField(final EditText etNotesField) {
        if (getContext() == null || etNotesField == null) return;
        
        File[] candidateDirs = new File[] {
                new File(android.os.Environment.getExternalStorageDirectory(), "Music/Recordings/Call Recordings"),
                new File(android.os.Environment.getExternalStorageDirectory(), "Recordings/Call"),
                new File(android.os.Environment.getExternalStorageDirectory(), "Recordings/Call Recordings"),
                new File(android.os.Environment.getExternalStorageDirectory(), "Recordings"),
                new File(android.os.Environment.getExternalStorageDirectory(), "Music/Recordings")
        };

        final List<File> audioFiles = new ArrayList<>();
        for (File dir : candidateDirs) {
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile()) {
                            String name = f.getName().toLowerCase();
                            boolean isAudio = name.endsWith(".mp3") || name.endsWith(".wav") || 
                                              name.endsWith(".m4a") || name.endsWith(".amr") || 
                                              name.endsWith(".aac") || name.endsWith(".ogg") ||
                                              name.endsWith(".mp4");
                            if (isAudio) {
                                audioFiles.add(f);
                            }
                        }
                    }
                }
            }
        }

        // Sort by date modified DESC (newest first)
        Collections.sort(audioFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        if (audioFiles.isEmpty()) {
            Toast.makeText(requireContext(), "No call recording files found on device storage.", Toast.LENGTH_LONG).show();
            return;
        }

        // Limit listing to top 50 recent files
        int limit = Math.min(audioFiles.size(), 50);
        String[] fileNames = new String[limit];
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
        for (int i = 0; i < limit; i++) {
            File f = audioFiles.get(i);
            String dateStr = sdf.format(new Date(f.lastModified()));
            double sizeMb = f.length() / (1024.0 * 1024.0);
            fileNames[i] = f.getName() + "\n(" + dateStr + " · " + String.format(Locale.getDefault(), "%.2f MB", sizeMb) + ")";
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Select Call Recording File")
                .setItems(fileNames, (dialog, which) -> {
                    File selectedFile = audioFiles.get(which);
                    
                    // Show feedback
                    Toast.makeText(requireContext(), "⌛ Transcribing selected file: " + selectedFile.getName(), Toast.LENGTH_LONG).show();
                    
                    Transcriber.transcribeCallRecording(requireContext(), selectedFile, new Transcriber.TranscriptionCallback() {
                        @Override
                        public void onSuccess(String text) {
                            if (!isAdded() || text == null || text.trim().isEmpty()) return;
                            
                            // Check OpenAI API Key
                            String openAiKey = requireContext().getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE).getString("openai_api_key", "").trim();
                            if (openAiKey.isEmpty()) {
                                // Fallback: No OpenAI key -> Save transcription raw
                                String currentNotes = etNotesField.getText().toString().trim();
                                etNotesField.setText(currentNotes.isEmpty() ? text : currentNotes + "\n" + text);
                                Toast.makeText(requireContext(), "Transcription success! Saved raw.", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            // Query OpenAI
                            Toast.makeText(requireContext(), "✨ Running AI analysis...", Toast.LENGTH_SHORT).show();
                            OpenAiClient.extractFields(requireContext(), text, new OpenAiClient.OpenAiCallback() {
                                @Override
                                public void onSuccess(JSONObject result) {
                                    if (!isAdded()) return;
                                    try {
                                        if (result.has("candidate_name") && !result.isNull("candidate_name") && activeEtCandidateName != null) {
                                            String current = activeEtCandidateName.getText().toString().trim();
                                            if (current.isEmpty()) {
                                                activeEtCandidateName.setText(result.getString("candidate_name"));
                                            }
                                        }
                                        if (result.has("company_name") && !result.isNull("company_name") && activeEtCompany != null) {
                                            String current = activeEtCompany.getText().toString().trim();
                                            if (current.isEmpty()) {
                                                activeEtCompany.setText(result.getString("company_name"));
                                            }
                                        }
                                        if (result.has("applied_role") && !result.isNull("applied_role") && activeEtAppliedRole != null) {
                                            String current = activeEtAppliedRole.getText().toString().trim();
                                            if (current.isEmpty()) {
                                                activeEtAppliedRole.setText(result.getString("applied_role"));
                                            }
                                        }
                                        if (result.has("present_round") && !result.isNull("present_round") && activeSpinnerRound != null) {
                                            setSpinnerSelection(activeSpinnerRound, result.getString("present_round"));
                                        }
                                        if (result.has("tentative_schedule") && !result.isNull("tentative_schedule") && activeEtTentativeSchedule != null) {
                                            activeEtTentativeSchedule.setText(result.getString("tentative_schedule"));
                                        }
                                        if (result.has("notice_period") && !result.isNull("notice_period") && activeEtNoticePeriod != null) {
                                            activeEtNoticePeriod.setText(result.getString("notice_period"));
                                        }
                                        if (result.has("main_agenda") && !result.isNull("main_agenda") && activeEtMainAgenda != null) {
                                            activeEtMainAgenda.setText(result.getString("main_agenda"));
                                        }
                                        if (result.has("next_steps") && !result.isNull("next_steps") && activeEtNextSteps != null) {
                                            activeEtNextSteps.setText(result.getString("next_steps"));
                                        }
                                        
                                        if (result.has("key_discussion_points") && activeEtNotes != null) {
                                            JSONArray arr = result.getJSONArray("key_discussion_points");
                                            StringBuilder sb = new StringBuilder();
                                            for (int i = 0; i < arr.length(); i++) {
                                                sb.append("• ").append(arr.getString(i)).append("\n");
                                            }
                                            activeEtNotes.setText(sb.toString().trim());
                                        }
                                        Toast.makeText(requireContext(), "AI fields updated successfully!", Toast.LENGTH_SHORT).show();
                                    } catch (Exception e) {
                                        DebugLogger.log(requireContext(), "Failed to parse OpenAI fields in dialog: " + e.getMessage());
                                        etNotesField.setText(text);
                                        Toast.makeText(requireContext(), "AI analysis failed. Pre-filled raw transcription.", Toast.LENGTH_LONG).show();
                                    }
                                }

                                @Override
                                public void onError(String error) {
                                    if (!isAdded()) return;
                                    etNotesField.setText(text);
                                    Toast.makeText(requireContext(), "AI analysis failed: " + error + ". Pre-filled raw.", Toast.LENGTH_LONG).show();
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            if (isAdded()) {
                                Toast.makeText(requireContext(), "Error transcribing: " + error, Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDateTimePicker(EditText et) {
        Calendar calendar = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (view, year, month, dayOfMonth) -> {
            calendar.set(Calendar.YEAR, year);
            calendar.set(Calendar.MONTH, month);
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);

            new TimePickerDialog(requireContext(), (timeView, hourOfDay, minute) -> {
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
                calendar.set(Calendar.MINUTE, minute);

                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd 'at' hh:mm a", Locale.getDefault());
                et.setText(format.format(calendar.getTime()));
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show();

        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void setSpinnerSelection(Spinner spinner, String value) {
        if (value == null || value.trim().isEmpty()) return;
        SpinnerAdapter adapter = spinner.getAdapter();
        if (adapter == null) return;
        String lowerVal = value.toLowerCase().trim();
        for (int i = 0; i < adapter.getCount(); i++) {
            String item = adapter.getItem(i).toString().toLowerCase();
            if (item.contains(lowerVal) || lowerVal.contains(item)) {
                spinner.setSelection(i);
                return;
            }
        }
    }
}

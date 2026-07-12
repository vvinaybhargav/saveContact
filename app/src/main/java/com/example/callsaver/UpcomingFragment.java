package com.example.callsaver;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
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
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class UpcomingFragment extends Fragment implements UpcomingInterviewsAdapter.OnInterviewClickListener {

    private RecyclerView rvUpcomingList;
    private View emptyStateLayout;
    private DatabaseHelper dbHelper;
    private UpcomingInterviewsAdapter adapter;
    private List<JobCall> allUpcomingList;
    private List<JobCall> filteredList;

    private String selectedStatus = "All";
    private View layoutFilterChips;
    private final String[] statuses = {"All", "Screening", "1st Round", "2nd Round", "Final Round", "HR / Salary"};
    private TextView[] chips;

    // Sort by first-log time (ascending, oldest first) or by most recent call activity
    // (descending, newest first).
    private boolean sortByFirstCall = false;
    private TextView chipSortFirstCall;
    private TextView chipSortRecentCall;

    private EditText activeDialogNotesField;
    private TextInputLayout activeDialogTilNotes;
    private View activeLlTranscriptionProgress;
    private TextView activeTvTranscriptionStatus;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_upcoming, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new DatabaseHelper(requireContext());

        rvUpcomingList = view.findViewById(R.id.rv_upcoming_list);
        emptyStateLayout = view.findViewById(R.id.upcoming_empty_state);
        layoutFilterChips = view.findViewById(R.id.layout_upcoming_chips);

        rvUpcomingList.setLayoutManager(new LinearLayoutManager(requireContext()));
        allUpcomingList = new ArrayList<>();
        filteredList = new ArrayList<>();
        adapter = new UpcomingInterviewsAdapter(requireContext(), filteredList, this);
        rvUpcomingList.setAdapter(adapter);

        setupFilterChips(view);
        setupSortChips(view);
        loadUpcomingInterviews();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadUpcomingInterviews();
    }

    private void loadUpcomingInterviews() {
        if (dbHelper == null) return;
        List<JobCall> allCalls = dbHelper.getAllJobCalls();
        allUpcomingList.clear();

        for (JobCall c : allCalls) {
            String status = c.getRoundStatus();
            if (status != null && (status.equals("Negative") || status.equals("Not Interested") || status.equals("Offered"))) {
                continue;
            }

            String schedule = c.getTentativeSchedule();
            if (schedule != null && !schedule.trim().isEmpty()) {
                allUpcomingList.add(c);
            } else if (status != null && (status.equals("Screening") || status.equals("1st Round") || status.equals("2nd Round") || status.equals("Final Round") || status.equals("HR / Salary"))) {
                allUpcomingList.add(c);
            }
        }

        filterList(selectedStatus);
    }

    private void setupFilterChips(View view) {
        int[] chipIds = {
                R.id.chip_upcoming_all, R.id.chip_upcoming_screening, R.id.chip_upcoming_1st,
                R.id.chip_upcoming_2nd, R.id.chip_upcoming_final, R.id.chip_upcoming_hr
        };

        chips = new TextView[chipIds.length];
        for (int i = 0; i < chipIds.length; i++) {
            final int index = i;
            chips[i] = view.findViewById(chipIds[i]);
            if (chips[i] != null) {
                chips[i].setOnClickListener(v -> {
                    selectedStatus = statuses[index];
                    updateChipsUI();
                    filterList(selectedStatus);
                });
            }
        }
        updateChipsUI();
    }

    private void updateChipsUI() {
        if (chips == null || getContext() == null) return;
        float density = getResources().getDisplayMetrics().density;
        int selectedColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.accent_indigo);
        int unselectedBg = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.divider);
        int unselectedText = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary);

        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;

            android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
            drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            drawable.setCornerRadius(18 * density); // Pill shape

            if (statuses[i].equals(selectedStatus)) {
                drawable.setColor(selectedColor);
                chips[i].setBackground(drawable);
                chips[i].setTextColor(android.graphics.Color.WHITE);
            } else {
                drawable.setColor(unselectedBg);
                chips[i].setBackground(drawable);
                chips[i].setTextColor(unselectedText);
            }
        }
    }

    private void setupSortChips(View view) {
        chipSortFirstCall = view.findViewById(R.id.chip_sort_first_call);
        chipSortRecentCall = view.findViewById(R.id.chip_sort_recent_call);
        if (chipSortFirstCall != null) {
            chipSortFirstCall.setOnClickListener(v -> {
                sortByFirstCall = true;
                updateSortChipsUI();
                filterList(selectedStatus);
            });
        }
        if (chipSortRecentCall != null) {
            chipSortRecentCall.setOnClickListener(v -> {
                sortByFirstCall = false;
                updateSortChipsUI();
                filterList(selectedStatus);
            });
        }
        updateSortChipsUI();
    }

    private void updateSortChipsUI() {
        if (chipSortFirstCall == null || chipSortRecentCall == null || getContext() == null) return;
        float density = getResources().getDisplayMetrics().density;
        int selectedColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.accent_indigo);
        int unselectedBg = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.divider);
        int unselectedText = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary);

        TextView[] sortChips = {chipSortFirstCall, chipSortRecentCall};
        for (int i = 0; i < sortChips.length; i++) {
            boolean selected = (i == 0) == sortByFirstCall;
            android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
            drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            drawable.setCornerRadius(18 * density);
            drawable.setColor(selected ? selectedColor : unselectedBg);
            sortChips[i].setBackground(drawable);
            sortChips[i].setTextColor(selected ? android.graphics.Color.WHITE : unselectedText);
        }
    }

    private void filterList(String status) {
        List<JobCall> filtered = new ArrayList<>();
        for (JobCall call : allUpcomingList) {
            boolean matchesStatus = status.equals("All") ||
                    (call.getRoundStatus() != null && call.getRoundStatus().equals(status));

            if (matchesStatus) {
                filtered.add(call);
            }
        }

        // Sort by first-log time (oldest first) or by most recent call activity
        // (newest first), per the selected sort chip. Calls whose interview time has
        // already passed without an update always float to the top regardless of sort.
        Collections.sort(filtered, (a, b) -> {
            boolean followUpA = FollowUpUtils.needsFollowUp(a);
            boolean followUpB = FollowUpUtils.needsFollowUp(b);
            if (followUpA != followUpB) {
                return followUpA ? -1 : 1;
            }
            if (sortByFirstCall) {
                return Long.compare(a.getTimestamp(), b.getTimestamp());
            }
            long[] ta = dbHelper.getFirstAndRecentCallTimes(a.getId());
            long[] tb = dbHelper.getFirstAndRecentCallTimes(b.getId());
            long recentA = ta[1] > 0 ? ta[1] : ta[0];
            long recentB = tb[1] > 0 ? tb[1] : tb[0];
            return Long.compare(recentB, recentA);
        });

        filteredList.clear();
        filteredList.addAll(filtered);
        adapter.notifyDataSetChanged();

        if (filteredList.isEmpty()) {
            emptyStateLayout.setVisibility(View.VISIBLE);
            rvUpcomingList.setVisibility(View.GONE);
        } else {
            emptyStateLayout.setVisibility(View.GONE);
            rvUpcomingList.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onInterviewClick(JobCall call) {
        showAddEditCallDialog(call);
    }

    private void showAddEditCallDialog(final JobCall editCall) {
        if (getContext() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_add_call, null);
        builder.setView(dialogView);

        TextView dialogTitle = dialogView.findViewById(R.id.dialog_title);
        EditText etPhone = dialogView.findViewById(R.id.et_phone);
        EditText etCompany = dialogView.findViewById(R.id.et_company);
        EditText etRecruiter = dialogView.findViewById(R.id.et_recruiter_name);
        EditText etTags = null;
        EditText etNotes = dialogView.findViewById(R.id.et_notes);
        LinearLayout llNotesTimeline = dialogView.findViewById(R.id.ll_notes_timeline);
        View labelNotes = dialogView.findViewById(R.id.label_notes);
        View llSkillsMatchSection = dialogView.findViewById(R.id.ll_skills_match_section);
        TextView tvSkillsMatching = dialogView.findViewById(R.id.tv_skills_matching);
        TextView tvSkillsNotMatching = dialogView.findViewById(R.id.tv_skills_not_matching);
        Spinner spinnerRound = dialogView.findViewById(R.id.spinner_round);

        EditText etCandidateName = null;
        EditText etAppliedRole = dialogView.findViewById(R.id.et_applied_role);
        EditText etTentativeSchedule = dialogView.findViewById(R.id.et_tentative_schedule);
        // Notice Period / Main Agenda / Next Steps were removed from the dialog UI;
        // these ids no longer exist in dialog_add_call.xml, so findViewById would
        // return null and crash on unguarded use. Keep them null and guard everywhere.
        EditText etNoticePeriod = null;
        EditText etMainAgenda = null;
        EditText etNextSteps = null;

        if (etTentativeSchedule != null) {
            etTentativeSchedule.setOnClickListener(v -> showDateTimePicker(etTentativeSchedule));
        }

        View btnTomorrow = dialogView.findViewById(R.id.btn_schedule_tomorrow);
        if (btnTomorrow != null && etTentativeSchedule != null) {
            btnTomorrow.setOnClickListener(v -> {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd 'at' hh:mm a", java.util.Locale.getDefault());
                etTentativeSchedule.setText(sdf.format(cal.getTime()));
            });
        }

        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_dialog_save);
        Button btnDelete = dialogView.findViewById(R.id.btn_dialog_delete);
        Button btnSaveContacts = dialogView.findViewById(R.id.btn_dialog_save_contacts);
        Button btnReminder = dialogView.findViewById(R.id.btn_dialog_reminder);
        TextInputLayout tilNotes = dialogView.findViewById(R.id.til_notes);

        activeDialogNotesField = etNotes;
        activeDialogTilNotes = tilNotes;
        activeLlTranscriptionProgress = dialogView.findViewById(R.id.ll_dialog_transcription_progress);
        activeTvTranscriptionStatus = dialogView.findViewById(R.id.tv_dialog_transcription_status);

        View tvDialogManualRecording = dialogView.findViewById(R.id.tv_dialog_manual_recording);
        if (tvDialogManualRecording != null) {
            tvDialogManualRecording.setOnClickListener(v -> showManualRecordingDialogForNotesField(etNotes));
        }

        tilNotes.setEndIconOnClickListener(v -> {
            Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak note...");
            try {
                startActivityForResult(intent, 200);
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Speech recognition is not supported.", Toast.LENGTH_SHORT).show();
            }
        });

        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.round_statuses, android.R.layout.simple_spinner_item);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRound.setAdapter(spinnerAdapter);

        if (editCall != null) {
            dialogTitle.setText("Edit Recruiter Lead");
            btnSave.setText("Update");
            btnDelete.setVisibility(View.VISIBLE);
            btnSaveContacts.setVisibility(View.VISIBLE);
            btnReminder.setVisibility(View.VISIBLE);

            etPhone.setText(editCall.getPhoneNumber());
            etCompany.setText(editCall.getCompanyName());
            etRecruiter.setText(editCall.getRecruiterName());
            if (etTags != null) etTags.setText(editCall.getTags());
            etNotes.setText("");

            if (etCandidateName != null) etCandidateName.setText(editCall.getCandidateName());
            etAppliedRole.setText(editCall.getAppliedRole());
            etTentativeSchedule.setText(editCall.getTentativeSchedule());

            int roundPos = spinnerAdapter.getPosition(editCall.getRoundStatus());
            spinnerRound.setSelection(roundPos >= 0 ? roundPos : 0);

            populateTimeline(llNotesTimeline, labelNotes, editCall.getId());
            populateSkillsMatch(llSkillsMatchSection, tvSkillsMatching, tvSkillsNotMatching, editCall);

            btnDelete.setOnClickListener(v -> {
                dbHelper.deleteJobCall(editCall.getId());
                Toast.makeText(requireContext(), "Lead deleted successfully", Toast.LENGTH_SHORT).show();
                loadUpcomingInterviews();
                builder.create().dismiss();
            });

            btnSaveContacts.setOnClickListener(v -> {
                String cName = etCandidateName != null ? etCandidateName.getText().toString().trim() : "";
                String cComp = etCompany.getText().toString().trim();
                String cRole = etAppliedRole.getText().toString().trim();
                String phone = etPhone.getText().toString().trim();

                Intent contactIntent = new Intent(Intent.ACTION_INSERT);
                contactIntent.setType(android.provider.ContactsContract.RawContacts.CONTENT_TYPE);
                contactIntent.putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, phone);
                contactIntent.putExtra(android.provider.ContactsContract.Intents.Insert.NAME, cName.isEmpty() ? "Recruiter" : cName);
                contactIntent.putExtra(android.provider.ContactsContract.Intents.Insert.COMPANY, cComp);
                contactIntent.putExtra(android.provider.ContactsContract.Intents.Insert.JOB_TITLE, cRole);
                startActivity(contactIntent);
            });

            btnReminder.setOnClickListener(v -> {
                String cComp = etCompany.getText().toString().trim();
                String cRole = etAppliedRole.getText().toString().trim();

                Calendar beginTime = Calendar.getInstance();
                beginTime.add(Calendar.DAY_OF_YEAR, 3);
                Calendar endTime = Calendar.getInstance();
                endTime.add(Calendar.DAY_OF_YEAR, 3);
                endTime.add(Calendar.HOUR, 1);

                Intent calendarIntent = new Intent(Intent.ACTION_INSERT)
                        .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTime.getTimeInMillis())
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endTime.getTimeInMillis())
                        .putExtra(android.provider.CalendarContract.Events.TITLE, "Interview follow up: " + cRole + " at " + cComp)
                        .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, "Follow up call with " + cComp)
                        .putExtra(android.provider.CalendarContract.Events.AVAILABILITY, android.provider.CalendarContract.Events.AVAILABILITY_BUSY);
                startActivity(calendarIntent);
            });

        } else {
            dialogTitle.setText("Add Recruiter Lead");
            btnSave.setText("Save");
            btnDelete.setVisibility(View.GONE);
            btnSaveContacts.setVisibility(View.GONE);
            btnReminder.setVisibility(View.GONE);
        }

        AlertDialog dialog = builder.create();

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String phone = etPhone.getText().toString().trim();
            String comp = etCompany.getText().toString().trim();
            String recruiterName = etRecruiter.getText().toString().trim();
            String tagsVal = etTags != null ? etTags.getText().toString().trim() : "";
            String notesVal = etNotes.getText().toString().trim();
            String roundVal = spinnerRound.getSelectedItem().toString();

            String candidate = etCandidateName != null ? etCandidateName.getText().toString().trim() : "";
            String role = etAppliedRole.getText().toString().trim();
            String schedule = etTentativeSchedule.getText().toString().trim();
            String notice = editCall != null ? editCall.getNoticePeriod() : "";
            String agenda = editCall != null ? editCall.getMainAgenda() : "";
            String nextStepsVal = editCall != null ? editCall.getNextSteps() : "";

            if (phone.isEmpty()) {
                etPhone.setError("Phone number is required");
                return;
            }

            if (editCall != null) {
                editCall.setPhoneNumber(phone);
                editCall.setCompanyName(comp);
                editCall.setRecruiterName(recruiterName);
                editCall.setTags(tagsVal);
                editCall.setRoundStatus(roundVal);

                editCall.setCandidateName(candidate);
                editCall.setAppliedRole(role);
                editCall.setTentativeSchedule(schedule);
                editCall.setNoticePeriod(notice);
                editCall.setMainAgenda(agenda);
                editCall.setNextSteps(nextStepsVal);

                dbHelper.updateJobCall(editCall);

                if (!notesVal.isEmpty()) {
                    dbHelper.insertNote(editCall.getId(), notesVal, System.currentTimeMillis());
                }

                Toast.makeText(requireContext(), "Lead updated successfully", Toast.LENGTH_SHORT).show();
            } else {
                JobCall newCall = new JobCall(phone, comp, roundVal, tagsVal, notesVal, 0, System.currentTimeMillis());
                newCall.setCandidateName(candidate);
                newCall.setAppliedRole(role);
                newCall.setRecruiterName(recruiterName);
                newCall.setTentativeSchedule(schedule);
                newCall.setNoticePeriod(notice);
                newCall.setMainAgenda(agenda);
                newCall.setNextSteps(nextStepsVal);

                long newId = dbHelper.insertJobCall(newCall);
                if (newId > 0 && !notesVal.isEmpty()) {
                    dbHelper.insertNote(newId, notesVal, System.currentTimeMillis());
                }
                Toast.makeText(requireContext(), "Lead saved successfully", Toast.LENGTH_SHORT).show();
            }

            loadUpcomingInterviews();
            dialog.dismiss();
        });

        dialog.setOnDismissListener(d -> {
            activeDialogNotesField = null;
            activeDialogTilNotes = null;
            activeLlTranscriptionProgress = null;
            activeTvTranscriptionStatus = null;
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();

        View parent = (View) dialogView.getParent();
        if (parent != null) {
            parent.setBackgroundResource(R.drawable.spinner_border);
        }
    }

    private void showDateTimePicker(final EditText etTarget) {
        final Calendar calendar = Calendar.getInstance();
        DatePickerDialog datePickerDialog = new DatePickerDialog(requireContext(), (view, year, month, dayOfMonth) -> {
            calendar.set(Calendar.YEAR, year);
            calendar.set(Calendar.MONTH, month);
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);

            TimePickerDialog timePickerDialog = new TimePickerDialog(requireContext(), (view1, hourOfDay, minute) -> {
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
                calendar.set(Calendar.MINUTE, minute);

                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd 'at' hh:mm a", Locale.getDefault());
                etTarget.setText(format.format(calendar.getTime()));
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false);
            timePickerDialog.show();
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH));
        datePickerDialog.show();
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

        Collections.sort(audioFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        if (audioFiles.isEmpty()) {
            Toast.makeText(requireContext(), "No call recording files found on device.", Toast.LENGTH_LONG).show();
            return;
        }

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
                    Toast.makeText(requireContext(), "⌛ Transcribing selected file: " + selectedFile.getName(), Toast.LENGTH_LONG).show();
                    if (activeLlTranscriptionProgress != null) {
                        activeLlTranscriptionProgress.setVisibility(View.VISIBLE);
                    }
                    if (activeTvTranscriptionStatus != null) {
                        activeTvTranscriptionStatus.setText("✨ Transcribing call recording via Deepgram...");
                    }

                    Transcriber.transcribeCallRecording(requireContext(), selectedFile, new Transcriber.TranscriptionCallback() {
                        @Override
                        public void onSuccess(String text) {
                            if (!isAdded() || text == null || text.trim().isEmpty()) return;

                            String openAiKey = requireContext().getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE).getString("openai_api_key", "").trim();
                            if (openAiKey.isEmpty()) {
                                if (activeLlTranscriptionProgress != null) {
                                    activeLlTranscriptionProgress.setVisibility(View.GONE);
                                }
                                String currentNotes = etNotesField.getText().toString().trim();
                                etNotesField.setText(currentNotes.isEmpty() ? text : currentNotes + "\n" + text);
                                Toast.makeText(requireContext(), "Transcription success! Saved raw.", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            if (activeTvTranscriptionStatus != null) {
                                activeTvTranscriptionStatus.setText("✨ Extracting fields using OpenAI...");
                            }
                            Toast.makeText(requireContext(), "✨ Running AI analysis...", Toast.LENGTH_SHORT).show();
                            OpenAiClient.extractFields(requireContext(), text, new OpenAiClient.OpenAiCallback() {
                                @Override
                                public void onSuccess(JSONObject result) {
                                    if (!isAdded()) return;
                                    try {
                                        // Wait, define local dialog fields variables to fill in
                                        View root = etNotesField.getRootView();
                                        EditText etCandidate = null;
                                        EditText etComp = root.findViewById(R.id.et_company);
                                        EditText etRole = root.findViewById(R.id.et_applied_role);
                                        Spinner spinRound = root.findViewById(R.id.spinner_round);
                                        EditText etSched = root.findViewById(R.id.et_tentative_schedule);
                                        EditText etNotice = root.findViewById(R.id.et_notice_period);
                                        EditText etAgenda = root.findViewById(R.id.et_main_agenda);
                                        EditText etNext = root.findViewById(R.id.et_next_steps);

                                        String candidate = optClean(result, "candidate_name", "");
                                        if (!candidate.isEmpty() && etCandidate != null) {
                                            etCandidate.setText(candidate);
                                        }
                                        String company = optClean(result, "company_name", "");
                                        if (!company.isEmpty() && etComp != null) {
                                            etComp.setText(company);
                                        }
                                        String role = optClean(result, "applied_role", "");
                                        if (!role.isEmpty() && etRole != null) {
                                            etRole.setText(role);
                                        }
                                        String round = optClean(result, "present_round", "");
                                        if (!round.isEmpty() && spinRound != null) {
                                            ArrayAdapter<CharSequence> adapter = (ArrayAdapter<CharSequence>) spinRound.getAdapter();
                                            if (adapter != null) {
                                                int pos = adapter.getPosition(round);
                                                spinRound.setSelection(pos >= 0 ? pos : 0);
                                            }
                                        }
                                        String schedule = optClean(result, "tentative_schedule", "");
                                        if (!schedule.isEmpty() && etSched != null) {
                                            etSched.setText(schedule);
                                        }
                                        String notice = optClean(result, "notice_period", "");
                                        if (!notice.isEmpty() && etNotice != null) {
                                            etNotice.setText(notice);
                                        }
                                        String agenda = optClean(result, "main_agenda", "");
                                        if (!agenda.isEmpty() && etAgenda != null) {
                                            etAgenda.setText(agenda);
                                        }
                                        String nextStepsVal = optClean(result, "next_steps", "");
                                        if (!nextStepsVal.isEmpty() && etNext != null) {
                                            etNext.setText(nextStepsVal);
                                        }

                                        if (result.has("key_discussion_points")) {
                                            JSONArray arr = result.getJSONArray("key_discussion_points");
                                            StringBuilder sb = new StringBuilder();
                                            for (int i = 0; i < arr.length(); i++) {
                                                sb.append("• ").append(arr.getString(i)).append("\n");
                                            }
                                            etNotesField.setText(sb.toString().trim());
                                        }
                                        Toast.makeText(requireContext(), "AI fields updated successfully!", Toast.LENGTH_SHORT).show();
                                    } catch (Exception e) {
                                        etNotesField.setText(text);
                                        Toast.makeText(requireContext(), "AI analysis failed. Pre-filled raw transcription.", Toast.LENGTH_LONG).show();
                                    } finally {
                                        if (activeLlTranscriptionProgress != null) {
                                            activeLlTranscriptionProgress.setVisibility(View.GONE);
                                        }
                                    }
                                }

                                @Override
                                public void onError(String error) {
                                    if (activeLlTranscriptionProgress != null) {
                                        activeLlTranscriptionProgress.setVisibility(View.GONE);
                                    }
                                    etNotesField.setText(text);
                                    Toast.makeText(requireContext(), "AI analysis failed: " + error + ". Pre-filled raw.", Toast.LENGTH_LONG).show();
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            if (activeLlTranscriptionProgress != null) {
                                activeLlTranscriptionProgress.setVisibility(View.GONE);
                            }
                            Toast.makeText(requireContext(), "Error transcribing: " + error, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void populateSkillsMatch(View section, TextView tvMatching, TextView tvNotMatching, JobCall call) {
        if (section == null || tvMatching == null || tvNotMatching == null || call == null) return;
        String matching = call.getMatchingSkills();
        String notMatching = call.getNotMatchingSkills();
        if ((matching == null || matching.isEmpty()) && (notMatching == null || notMatching.isEmpty())) {
            section.setVisibility(View.GONE);
            return;
        }
        section.setVisibility(View.VISIBLE);
        tvMatching.setText(matching == null || matching.isEmpty() ? "-" : matching);
        tvNotMatching.setText(notMatching == null || notMatching.isEmpty() ? "-" : notMatching);
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

    private static class TimelineRow {
        long ts;
        String text;
        boolean isNote;
        long noteId;
    }
    private String optClean(org.json.JSONObject json, String key, String fallback) {
        if (json == null || json.isNull(key)) return fallback;
        String val = json.optString(key, fallback).trim();
        if (val.equalsIgnoreCase("null")) return fallback;
        return val;
    }
}

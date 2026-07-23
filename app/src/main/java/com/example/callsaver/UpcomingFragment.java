package com.example.callsaver;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class UpcomingFragment extends Fragment implements UpcomingInterviewsAdapter.OnInterviewClickListener {

    private RecyclerView rvUpcomingList;
    private View emptyStateLayout;
    private DatabaseHelper dbHelper;
    private UpcomingInterviewsAdapter adapter;
    private List<JobCall> allUpcomingList;
    private List<JobCall> filteredList;

    // Multi-select: any card matching ANY selected filter is shown (union). "All"
    // is mutually exclusive with everything else - picking a specific filter clears
    // it, and clearing every specific filter falls back to "All".
    private final Set<String> selectedFilters = new LinkedHashSet<>(Collections.singletonList("All"));
    private View layoutFilterChips;
    private final String[] statuses = {
            "All", "Needs Update", "Scheduled", "No Follow-up Needed"
    };
    private TextView[] chips;

    // Sort by first-log time (ascending, oldest first) or by most recent call activity
    // (descending, newest first).
    private boolean sortByFirstCall = false;
    private TextView chipSortFirstCall;
    private TextView chipSortRecentCall;

    private static final int REQ_CODE_SPEECH_INPUT = 1001;
    private static final int REQ_CODE_PICK_JD_SCREENSHOT = 800;
    private String[] screenshotPaths = new String[3];
    private final int[] containerIds = {R.id.fl_jd_screenshot_container_1, R.id.fl_jd_screenshot_container_2, R.id.fl_jd_screenshot_container_3};
    private final int[] previewIds = {R.id.iv_jd_screenshot_preview_1, R.id.iv_jd_screenshot_preview_2, R.id.iv_jd_screenshot_preview_3};
    private final int[] removeIds = {R.id.btn_remove_jd_screenshot_1, R.id.btn_remove_jd_screenshot_2, R.id.btn_remove_jd_screenshot_3};
    private View activeDialogView;

    private com.google.android.material.tabs.TabLayout tabLayoutDates;
    private String selectedTabKey = null;
    private boolean isUpdatingTabs = false;
    private boolean activeDialogManualUploadUsed = false;
    private boolean activeDialogManualUploadAIFailed = false;
    private EditText activeEtInterestRating;

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

        rvUpcomingList.setLayoutManager(new LinearLayoutManager(requireContext()));
        allUpcomingList = new ArrayList<>();
        filteredList = new ArrayList<>();
        adapter = new UpcomingInterviewsAdapter(requireContext(), filteredList, this);
        rvUpcomingList.setAdapter(adapter);

        setupFilterSpinner(view);
        tabLayoutDates = view.findViewById(R.id.tab_layout_upcoming_dates);
        setupDateTabs();
        loadUpcomingInterviews();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_SPEECH_INPUT && resultCode == android.app.Activity.RESULT_OK && data != null) {
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
        } else if (requestCode >= REQ_CODE_PICK_JD_SCREENSHOT && requestCode < REQ_CODE_PICK_JD_SCREENSHOT + 3 
                && resultCode == android.app.Activity.RESULT_OK && data != null) {
            android.net.Uri selectedUri = data.getData();
            if (selectedUri != null) {
                int index = requestCode - REQ_CODE_PICK_JD_SCREENSHOT;
                handleJdScreenshotSelectedAtIndex(index, selectedUri);
            }
        }
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
            } else if (status != null && (status.equals("First time") || status.equals("Screening") || status.equals("Interested") || status.equals("1st Round") || status.equals("2nd Round") || status.equals("Final Round") || status.equals("HR / Salary"))) {
                allUpcomingList.add(c);
            }
        }

        filterList();
    }

    private void setupFilterSpinner(View view) {
        Spinner spinnerFilter = view.findViewById(R.id.spinner_upcoming_filter);
        if (spinnerFilter != null && getContext() != null) {
            ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_item, statuses);
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerFilter.setAdapter(spinnerAdapter);
            spinnerFilter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    String selected = statuses[position];
                    selectedFilters.clear();
                    selectedFilters.add(selected);
                    filterList();
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }
    }

    /**
     * True if this call matches ANY of the currently selected filter chips (union),
     * combining round-stage filters (Screening, 1st Round, ...) with computed
     * follow-up-state filters (Needs Update / Scheduled / No Follow-up Needed).
     */
    private boolean matchesSelectedFilters(JobCall call) {
        if (selectedFilters.contains("All")) return true;

        for (String filter : selectedFilters) {
            switch (filter) {
                case "Needs Update":
                    if (FollowUpUtils.needsFollowUp(call)) return true;
                    break;
                case "Scheduled": {
                    long scheduleMillis = FollowUpUtils.parseScheduleMillis(call.getTentativeSchedule());
                    if (scheduleMillis > System.currentTimeMillis()) return true;
                    break;
                }
                case "No Follow-up Needed":
                    if (!FollowUpUtils.needsFollowUp(call)) return true;
                    break;
                default:
                    if (call.getRoundStatus() != null && call.getRoundStatus().equals(filter)) return true;
                    break;
            }
        }
        return false;
    }

    private void setupDateTabs() {
        if (tabLayoutDates == null) return;
        tabLayoutDates.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                if (isUpdatingTabs) return;
                if (tab.getTag() != null) {
                    selectedTabKey = (String) tab.getTag();
                }
                filterList();
            }
            @Override
            public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
        });
    }

    private String getOrdinal(int n) {
        if (n >= 11 && n <= 13) {
            return n + "th";
        }
        switch (n % 10) {
            case 1:  return n + "st";
            case 2:  return n + "nd";
            case 3:  return n + "rd";
            default: return n + "th";
        }
    }

    private long getStartOfDay(long timeMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(timeMs);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private String getTabDateKey(long millis) {
        if (millis < 0) {
            return "Past";
        }
        java.util.Calendar cal = java.util.Calendar.getInstance();
        long startOfToday = getStartOfDay(cal.getTimeInMillis());
        if (millis < startOfToday) {
            return "Past";
        }
        java.text.SimpleDateFormat sdfGroup = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
        return sdfGroup.format(new java.util.Date(millis));
    }

    private String getTabLabel(String key, int count) {
        if (key.equals("Past")) {
            return "Past\n(" + count + ")";
        }
        if (key.equals("All")) {
            return "All\n(" + count + ")";
        }
        try {
            java.text.SimpleDateFormat sdfGroup = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
            java.util.Date date = sdfGroup.parse(key);
            
            java.util.Calendar targetCal = java.util.Calendar.getInstance();
            targetCal.setTime(date);
            int dayNum = targetCal.get(java.util.Calendar.DAY_OF_MONTH);
            String ordinalDay = getOrdinal(dayNum);
            return ordinalDay + "\n(" + count + ")";
        } catch (Exception e) {
            return key + "\n(" + count + ")";
        }
    }

    private void updateDateTabs(List<JobCall> chipFilteredList) {
        if (tabLayoutDates == null) return;

        java.util.Map<String, Integer> countsMap = new java.util.HashMap<>();
        countsMap.put("Past", 0);
        countsMap.put("All", chipFilteredList.size());
        
        java.util.List<String> futureDates = new java.util.ArrayList<>();
        
        java.util.Calendar cal = java.util.Calendar.getInstance();
        java.text.SimpleDateFormat sdfGroup = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
        String todayKey = sdfGroup.format(cal.getTime());

        for (JobCall call : chipFilteredList) {
            long scheduleMillis = FollowUpUtils.parseScheduleMillis(call.getTentativeSchedule());
            String key = getTabDateKey(scheduleMillis);
            if (!key.equals("Past")) {
                if (!countsMap.containsKey(key)) {
                    countsMap.put(key, 0);
                    futureDates.add(key);
                }
            }
            countsMap.put(key, countsMap.get(key) + 1);
        }
        
        java.util.Collections.sort(futureDates);

        java.util.List<String> tabKeys = new java.util.ArrayList<>();
        if (futureDates.contains(todayKey)) {
            tabKeys.add(todayKey);
            futureDates.remove(todayKey);
        }
        tabKeys.addAll(futureDates);
        tabKeys.add("All");
        tabKeys.add("Past");

        if (selectedTabKey == null || !tabKeys.contains(selectedTabKey)) {
            if (tabKeys.contains(todayKey)) {
                selectedTabKey = todayKey;
            } else if (!tabKeys.isEmpty() && !tabKeys.get(0).equals("All") && !tabKeys.get(0).equals("Past")) {
                selectedTabKey = tabKeys.get(0);
            } else {
                selectedTabKey = "All";
            }
        }

        isUpdatingTabs = true;
        tabLayoutDates.removeAllTabs();
        
        int selectIndex = 0;
        for (int i = 0; i < tabKeys.size(); i++) {
            String key = tabKeys.get(i);
            String label = getTabLabel(key, countsMap.containsKey(key) ? countsMap.get(key) : 0);
            com.google.android.material.tabs.TabLayout.Tab tab = tabLayoutDates.newTab().setText(label);
            tab.setTag(key);
            tabLayoutDates.addTab(tab);
            if (key.equals(selectedTabKey)) {
                selectIndex = i;
            }
        }
        
        com.google.android.material.tabs.TabLayout.Tab tabToSelect = tabLayoutDates.getTabAt(selectIndex);
        if (tabToSelect != null) {
            tabToSelect.select();
        }
        isUpdatingTabs = false;
    }

    private void filterList() {
        List<JobCall> chipFiltered = new ArrayList<>();
        for (JobCall call : allUpcomingList) {
            if (matchesSelectedFilters(call)) {
                chipFiltered.add(call);
            }
        }

        updateDateTabs(chipFiltered);

        List<JobCall> filtered = new ArrayList<>();
        
        for (JobCall call : chipFiltered) {
            long scheduleMillis = FollowUpUtils.parseScheduleMillis(call.getTentativeSchedule());
            String key = getTabDateKey(scheduleMillis);
            
            if (selectedTabKey == null || selectedTabKey.equals("All")) {
                filtered.add(call);
            } else if (selectedTabKey.equals("Past")) {
                if (key.equals("Past")) {
                    filtered.add(call);
                }
            } else {
                if (key.equals(selectedTabKey)) {
                    filtered.add(call);
                }
            }
        }

        Collections.sort(filtered, (a, b) -> {
            boolean followUpA = FollowUpUtils.needsFollowUp(a);
            boolean followUpB = FollowUpUtils.needsFollowUp(b);
            if (followUpA != followUpB) {
                return followUpA ? -1 : 1;
            }
            long ta = FollowUpUtils.parseScheduleMillis(a.getTentativeSchedule());
            long tb = FollowUpUtils.parseScheduleMillis(b.getTentativeSchedule());
            if (ta < 0 && tb < 0) {
                return Long.compare(a.getTimestamp(), b.getTimestamp());
            }
            if (ta < 0) return 1;
            if (tb < 0) return -1;
            return Long.compare(ta, tb);
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

    @Override
    public void onFollowUpClick(final JobCall call) {
        showQuickUpdateDialog(call);
    }

    private void showQuickUpdateDialog(final JobCall call) {
        if (getContext() == null) return;
        
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Quick Status Update");
        
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);
        
        // Status Spinner Label
        TextView lblStatus = new TextView(requireContext());
        lblStatus.setText("Interview Round / Status:");
        lblStatus.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary));
        lblStatus.setPadding(0, 0, 0, 15);
        layout.addView(lblStatus);
        
        // Status Spinner
        final Spinner spinner = new Spinner(requireContext());
        String[] spinnerStatuses = {"First time", "Screening", "Interested", "1st Round", "2nd Round", "Final Round", "HR / Salary", "Offered", "Not Interested", "Negative"};
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, spinnerStatuses);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);
        
        // Pre-select current status
        if (call.getRoundStatus() != null) {
            String currentStatus = call.getRoundStatus();
            int pos = spinnerAdapter.getPosition(currentStatus);
            spinner.setSelection(pos >= 0 ? pos : 0);
        }
        layout.addView(spinner);
        
        // Spacing
        View spacing1 = new View(requireContext());
        spacing1.setMinimumHeight(30);
        layout.addView(spacing1);
        
        // "Yet to get an update" Checkbox
        final com.google.android.material.checkbox.MaterialCheckBox cbYetToUpdate = new com.google.android.material.checkbox.MaterialCheckBox(requireContext());
        cbYetToUpdate.setText("Yet to get an update (clears schedule)");
        cbYetToUpdate.setChecked(false);
        layout.addView(cbYetToUpdate);
        
        // Spacing
        View spacing2 = new View(requireContext());
        spacing2.setMinimumHeight(30);
        layout.addView(spacing2);
        
        // Note Label
        TextView lblNote = new TextView(requireContext());
        lblNote.setText("Log a note:");
        lblNote.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary));
        lblNote.setPadding(0, 0, 0, 15);
        layout.addView(lblNote);
        
        // Note EditText
        final EditText etNote = new EditText(requireContext());
        etNote.setHint("Write what happened (optional)...");
        etNote.setMinLines(2);
        layout.addView(etNote);
        
        builder.setView(layout);
        
        builder.setPositiveButton("Save", (d, w) -> {
            String selectedStatus = spinner.getSelectedItem().toString();
            String noteText = etNote.getText().toString().trim();
            boolean isYetToUpdate = cbYetToUpdate.isChecked();
            
            // Check if status changed to terminal, or if 'Yet to get an update' checked
            boolean isTerminal = selectedStatus.equals("Negative") || selectedStatus.equals("Not Interested") || selectedStatus.equals("Offered");
            
            call.setRoundStatus(selectedStatus);
            if (isYetToUpdate || isTerminal) {
                call.setTentativeSchedule(""); // Clear schedule date/time
            }
            
            dbHelper.updateJobCall(call);
            
            String finalNote = "";
            if (isYetToUpdate) {
                finalNote = "• Yet to get an update";
                if (!noteText.isEmpty()) {
                    finalNote += "\n• " + noteText;
                }
            } else if (!noteText.isEmpty()) {
                finalNote = "• " + noteText;
            }
            
            if (!finalNote.isEmpty()) {
                dbHelper.insertNote(call.getId(), finalNote, System.currentTimeMillis(), DatabaseHelper.NOTE_SOURCE_MANUAL);
            }
            
            Toast.makeText(requireContext(), "Status updated successfully!", Toast.LENGTH_SHORT).show();
            loadUpcomingInterviews();
        });
        
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showAddEditCallDialog(final JobCall editCall) {
        if (getContext() == null) return;

        activeDialogManualUploadUsed = false;
        activeDialogManualUploadAIFailed = false;

        // Initialize screenshot paths
        for (int i = 0; i < 3; i++) {
            screenshotPaths[i] = "";
        }
        if (editCall != null) {
            String imagePathsStr = editCall.getJdImagePath();
            if (imagePathsStr != null && !imagePathsStr.trim().isEmpty()) {
                String[] parts = imagePathsStr.split(",");
                for (int i = 0; i < Math.min(parts.length, 3); i++) {
                    screenshotPaths[i] = parts[i].trim();
                }
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_add_call, null);
        builder.setView(dialogView);
        activeDialogView = dialogView;

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
        TextInputLayout tilTentativeSchedule = dialogView.findViewById(R.id.til_tentative_schedule);
        if (tilTentativeSchedule != null) {
            tilTentativeSchedule.setEndIconOnClickListener(v -> etTentativeSchedule.setText(""));
        }

        Spinner spinnerInterestStatus = dialogView.findViewById(R.id.spinner_interest_status);

        activeDialogNotesField = etNotes;
        activeDialogTilNotes = dialogView.findViewById(R.id.til_notes);
        activeLlTranscriptionProgress = dialogView.findViewById(R.id.ll_dialog_transcription_progress);
        activeTvTranscriptionStatus = dialogView.findViewById(R.id.tv_dialog_transcription_status);

        if (etTentativeSchedule != null) {
            etTentativeSchedule.setOnClickListener(v -> showDateTimePicker(etTentativeSchedule));
        }

        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_dialog_save);
        Button btnDelete = dialogView.findViewById(R.id.btn_dialog_delete);
        Button btnSaveContacts = dialogView.findViewById(R.id.btn_dialog_save_contacts);
        Button btnShowBanner = dialogView.findViewById(R.id.btn_dialog_show_banner);

        TextInputLayout tilNotes = dialogView.findViewById(R.id.til_notes);

        View tvDialogManualRecording = dialogView.findViewById(R.id.tv_dialog_manual_recording);
        if (tvDialogManualRecording != null) {
            Long autoSaveJobId = (editCall != null && editCall.getId() > 0) ? (long) editCall.getId() : null;
            tvDialogManualRecording.setOnClickListener(v -> showManualRecordingDialogForNotesField(etNotes, autoSaveJobId,
                    llNotesTimeline, labelNotes, llSkillsMatchSection, tvSkillsMatching, tvSkillsNotMatching));
        }

        if (tilNotes != null) {
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
        }

        // JD screenshot previews setup
        View btnUploadJd = dialogView.findViewById(R.id.btn_upload_jd_screenshot);
        if (btnUploadJd != null) {
            btnUploadJd.setOnClickListener(v -> {
                int firstEmptyIdx = -1;
                for (int i = 0; i < 3; i++) {
                    if (screenshotPaths[i] == null || screenshotPaths[i].trim().isEmpty()) {
                        firstEmptyIdx = i;
                        break;
                    }
                }
                if (firstEmptyIdx == -1) {
                    Toast.makeText(requireContext(), "Maximum 3 screenshots allowed.", Toast.LENGTH_SHORT).show();
                } else {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    startActivityForResult(intent, REQ_CODE_PICK_JD_SCREENSHOT + firstEmptyIdx);
                }
            });
        }

        updateScreenshotPreviews(dialogView);

        // Bind Spinner choices
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.round_statuses, android.R.layout.simple_spinner_item);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRound.setAdapter(spinnerAdapter);

        // Bind interest status choices
        String[] interestOptions = {"", "Interested", "Not Interested"};
        ArrayAdapter<String> interestAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, interestOptions);
        interestAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerInterestStatus != null) {
            spinnerInterestStatus.setAdapter(interestAdapter);
        }

        final AlertDialog alertDialog = builder.create();
        alertDialog.setOnDismissListener(dialog -> {
            activeDialogView = null;
            activeDialogNotesField = null;
            activeDialogTilNotes = null;
            activeLlTranscriptionProgress = null;
            activeTvTranscriptionStatus = null;
        });

        // Configure Dialog Mode (Edit vs. Add)
        if (editCall != null && editCall.getId() > 0) {
            dialogTitle.setText(R.string.title_edit_job_call);
            etPhone.setText(editCall.getPhoneNumber());
            etCompany.setText(editCall.getCompanyName());
            etRecruiter.setText(editCall.getRecruiterName());
            if (etTags != null) etTags.setText(editCall.getTags());
            
            if (etCandidateName != null) etCandidateName.setText(editCall.getCandidateName());
            etAppliedRole.setText(editCall.getAppliedRole());
            etTentativeSchedule.setText(editCall.getTentativeSchedule());

            if (spinnerInterestStatus != null) {
                int pos = interestAdapter.getPosition(editCall.getInterestRating());
                spinnerInterestStatus.setSelection(pos >= 0 ? pos : 0);
            }

            // Calls + notes are shown as a merged timeline below; the field adds a new note.
            populateTimeline(llNotesTimeline, labelNotes, editCall.getId());
            populateSkillsMatch(llSkillsMatchSection, tvSkillsMatching, tvSkillsNotMatching, editCall);
            btnSave.setText(R.string.btn_update);
            btnDelete.setVisibility(View.VISIBLE);
            btnShowBanner.setVisibility(View.VISIBLE);

            // Show/hide 'Save to Contacts' button based on existence (disabled, always hide)
            btnSaveContacts.setVisibility(View.GONE);

            // Set spinner selection
            if (editCall.getRoundStatus() != null) {
                int position = spinnerAdapter.getPosition(editCall.getRoundStatus());
                spinnerRound.setSelection(position >= 0 ? position : 0);
            }
        } else {
            dialogTitle.setText(R.string.title_add_job_call);
            btnSave.setText(R.string.btn_add);
            btnDelete.setVisibility(View.GONE);
            btnSaveContacts.setVisibility(View.GONE);
            btnShowBanner.setVisibility(View.GONE);
            if (editCall != null) {
                etPhone.setText(editCall.getPhoneNumber());
                
                if (editCall.getId() <= 0) {
                    SimpleDateFormat sdfNotes = new SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault());
                    String formattedDate = sdfNotes.format(new java.util.Date(editCall.getTimestamp()));
                    int durSec = editCall.getDuration();
                    String formattedDuration = durSec + "s";
                    if (durSec >= 60) {
                        formattedDuration = (durSec / 60) + "m " + (durSec % 60) + "s";
                    }
                    etNotes.setText("[Unlogged Call: End Time " + formattedDate + ", Duration: " + formattedDuration + "]\n");
                }
                
                // Auto pre-fill if call is already tracked in SQLite
                JobCall existingCall = dbHelper.getJobCallByNumber(requireContext(), editCall.getPhoneNumber());
                if (existingCall != null) {
                    etCompany.setText(existingCall.getCompanyName());
                    if (etTags != null) etTags.setText(existingCall.getTags());
                    if (existingCall.getRoundStatus() != null) {
                        int position = spinnerAdapter.getPosition(existingCall.getRoundStatus());
                        spinnerRound.setSelection(position >= 0 ? position : 0);
                    }
                    if (etCandidateName != null) etCandidateName.setText(existingCall.getCandidateName());
                    etAppliedRole.setText(existingCall.getAppliedRole());
                    etTentativeSchedule.setText(existingCall.getTentativeSchedule());
                    if (spinnerInterestStatus != null) {
                        int pos = interestAdapter.getPosition(existingCall.getInterestRating());
                        spinnerInterestStatus.setSelection(pos >= 0 ? pos : 0);
                    }
                }
            }
        }

        btnCancel.setOnClickListener(v -> alertDialog.dismiss());

        // Preview the in-call banner using whatever is currently in the dialog's fields
        btnShowBanner.setOnClickListener(v -> {
            if (editCall == null || editCall.getId() <= 0) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(requireContext())) {
                Toast.makeText(requireContext(), "Overlay permission is required to show the banner. Enable it in Settings.", Toast.LENGTH_LONG).show();
                return;
            }
            String previewPhone = etPhone.getText().toString().trim();
            if (previewPhone.isEmpty()) previewPhone = editCall.getPhoneNumber();
            Intent overlayIntent = new Intent(requireContext(), CallerIdService.class);
            overlayIntent.putExtra("phone_number", previewPhone);
            overlayIntent.putExtra("company_name", etCompany.getText().toString().trim());
            overlayIntent.putExtra("round_status", spinnerRound.getSelectedItem() != null ? spinnerRound.getSelectedItem().toString() : editCall.getRoundStatus());
            overlayIntent.putExtra("tags", etTags != null ? etTags.getText().toString().trim() : editCall.getTags());
            overlayIntent.putExtra("job_call_id", (long) editCall.getId());
            overlayIntent.putExtra("recruiter_name", etRecruiter.getText().toString().trim());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(overlayIntent);
            } else {
                requireContext().startService(overlayIntent);
            }
            Toast.makeText(requireContext(), "Showing banner preview - swipe it away or tap its notification to dismiss.", Toast.LENGTH_LONG).show();
        });

        // Delete click action
        btnDelete.setOnClickListener(v -> {
            if (editCall != null) {
                dbHelper.deleteJobCall(editCall.getId());
                Toast.makeText(requireContext(), R.string.msg_deleted, Toast.LENGTH_SHORT).show();
                loadUpcomingInterviews();
                alertDialog.dismiss();
            }
        });

        // Save / Update click action
        btnSave.setOnClickListener(v -> {
            String phone = etPhone.getText().toString().trim();
            String company = etCompany.getText().toString().trim();
            String recruiter = etRecruiter.getText().toString().trim();
            String tags = etTags != null ? etTags.getText().toString().trim() : "";
            String noteToAdd = etNotes != null ? etNotes.getText().toString().trim() : "";
            String round = spinnerRound.getSelectedItem().toString();

            String candidate = etCandidateName != null ? etCandidateName.getText().toString().trim() : "";
            String role = etAppliedRole.getText().toString().trim();
            String schedule = etTentativeSchedule.getText().toString().trim();
            String interestRatingVal = spinnerInterestStatus != null ? spinnerInterestStatus.getSelectedItem().toString() : "";
            String noteSource = activeDialogManualUploadUsed
                    ? DatabaseHelper.NOTE_SOURCE_MANUAL : DatabaseHelper.NOTE_SOURCE_CALL;

            if (phone.isEmpty()) {
                Toast.makeText(requireContext(), R.string.msg_phone_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                if (screenshotPaths[i] != null && !screenshotPaths[i].trim().isEmpty()) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(screenshotPaths[i]);
                }
            }
            String screenshotsVal = sb.toString();

            if (editCall != null && editCall.getId() > 0) {
                // Update mode
                editCall.setPhoneNumber(phone);
                editCall.setCompanyName(company);
                editCall.setRecruiterName(recruiter);
                editCall.setTags(tags);
                editCall.setRoundStatus(round);
                
                editCall.setCandidateName(candidate);
                editCall.setAppliedRole(role);
                editCall.setTentativeSchedule(schedule);
                editCall.setJdImagePath(screenshotsVal);
                editCall.setInterestRating(interestRatingVal);

                dbHelper.updateJobCall(editCall);
                dbHelper.linkPhoneToJob(editCall.getId(), phone, recruiter);
                boolean noteWasFromManualUpload = activeDialogManualUploadUsed;
                boolean noteFailedAI = activeDialogManualUploadAIFailed;
                long insertedNoteId = -1;
                if (!noteToAdd.isEmpty()) {
                    insertedNoteId = dbHelper.insertNote(editCall.getId(), noteToAdd, System.currentTimeMillis(), noteSource);
                }

                Toast.makeText(requireContext(), "Log updated!", Toast.LENGTH_SHORT).show();

                // Stay on this dialog and show the updated result instead of closing
                activeDialogManualUploadUsed = false;
                activeDialogManualUploadAIFailed = false;
                if (etNotes != null) etNotes.setText("");
                populateTimeline(llNotesTimeline, labelNotes, editCall.getId());
                loadUpcomingInterviews();

                if (!noteToAdd.isEmpty() && (!noteWasFromManualUpload || noteFailedAI)) {
                    runAiOnManualNote(editCall.getId(), insertedNoteId, noteToAdd, spinnerRound, etTentativeSchedule,
                            llNotesTimeline, labelNotes, llSkillsMatchSection, tvSkillsMatching, tvSkillsNotMatching);
                }
                return;
            } else {
                // Insert mode
                JobCall existingCall = null;
                if (!company.isEmpty()) {
                    existingCall = dbHelper.getJobCallByCompany(company);
                }

                if (existingCall != null) {
                    dbHelper.linkPhoneToJob(existingCall.getId(), phone, recruiter);
                    if (!noteToAdd.isEmpty()) {
                        dbHelper.insertNote(existingCall.getId(), noteToAdd, System.currentTimeMillis(), noteSource);
                    }
                    
                    existingCall.setCandidateName(candidate);
                    existingCall.setAppliedRole(role);
                    existingCall.setTentativeSchedule(schedule);
                    existingCall.setRoundStatus(round);
                    if (!recruiter.isEmpty()) {
                        existingCall.setRecruiterName(recruiter);
                    }
                    existingCall.setJdImagePath(screenshotsVal);
                    existingCall.setInterestRating(interestRatingVal);
                    dbHelper.updateJobCall(existingCall);
                    
                    Toast.makeText(requireContext(), "Linked to existing company " + existingCall.getCompanyName(), Toast.LENGTH_LONG).show();
                } else {
                    long callTime = System.currentTimeMillis();
                    int callDuration = 0;
                    if (editCall != null && editCall.getId() <= 0) {
                        callTime = editCall.getTimestamp();
                        callDuration = editCall.getDuration();
                    }
                    JobCall newCall = new JobCall(phone, company, round, tags, "", callDuration, callTime);
                    newCall.setRecruiterName(recruiter);
                    newCall.setCandidateName(candidate);
                    newCall.setAppliedRole(role);
                    newCall.setTentativeSchedule(schedule);
                    newCall.setKeyDiscussionPoints(noteToAdd);
                    newCall.setJdImagePath(screenshotsVal);
                    newCall.setInterestRating(interestRatingVal);

                    long newId = dbHelper.insertJobCall(newCall);
                    if (newId != -1 && !noteToAdd.isEmpty()) {
                        dbHelper.insertNote(newId, noteToAdd, System.currentTimeMillis(), noteSource);
                    }
                    Toast.makeText(requireContext(), "Call logged to tracker!", Toast.LENGTH_SHORT).show();
                }
            }

            loadUpcomingInterviews();
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

    private void showManualRecordingDialogForNotesField(final EditText etNotesField, final Long autoSaveJobId,
                                                          final LinearLayout timelineContainer, final View timelineLabel,
                                                          final View skillsSection, final TextView tvSkillsMatchingRef,
                                                          final TextView tvSkillsNotMatchingRef) {
        if (getContext() == null || etNotesField == null) return;

        File[] candidateDirs = CallRecordingScanner.getCandidateDirsForBrowsing(requireContext());

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
                    String notesText = etNotesField.getText().toString().trim();
                    if (notesText.isEmpty()) {
                        Toast.makeText(requireContext(), "Please enter notes first to analyze.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (activeLlTranscriptionProgress != null) {
                        activeLlTranscriptionProgress.setVisibility(View.VISIBLE);
                    }
                    if (activeTvTranscriptionStatus != null) {
                        activeTvTranscriptionStatus.setText("✨ Extracting fields using OpenAI...");
                    }
                    Toast.makeText(requireContext(), "✨ Running AI analysis...", Toast.LENGTH_SHORT).show();
                    OpenAiClient.extractFields(requireContext(), notesText, new OpenAiClient.OpenAiCallback() {
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

                                        String notesFromPoints = "";
                                        if (result.has("key_discussion_points")) {
                                            JSONArray arr = result.getJSONArray("key_discussion_points");
                                            StringBuilder sb = new StringBuilder();
                                            for (int i = 0; i < arr.length(); i++) {
                                                sb.append("• ").append(arr.getString(i)).append("\n");
                                            }
                                            notesFromPoints = sb.toString().trim();
                                            etNotesField.setText(notesFromPoints);
                                        }

                                        // Auto-save straight to the DB, same as a real call - the user
                                        // shouldn't have to also remember to tap Update after uploading a
                                        // recording. Only possible for an existing lead.
                                        if (autoSaveJobId != null && dbHelper != null) {
                                            JobCall current = dbHelper.getJobCallById(autoSaveJobId);
                                            if (current != null) {
                                                if (!company.isEmpty() && current.getCompanyName().isEmpty()) current.setCompanyName(company);
                                                if (!role.isEmpty() && current.getAppliedRole().isEmpty()) current.setAppliedRole(role);
                                                if (!candidate.isEmpty() && current.getCandidateName().isEmpty()) current.setCandidateName(candidate);
                                                if (!schedule.isEmpty()) current.setTentativeSchedule(schedule);
                                                String normalizedRound = OpenAiClient.normalizeRoundStatus(round, current.getRoundStatus());
                                                if (OpenAiClient.shouldUpdateRoundStatus(current.getRoundStatus(), normalizedRound)) {
                                                    current.setRoundStatus(normalizedRound);
                                                }
                                                String sentimentComment = optClean(result, "sentiment_comment", "");
                                                String userInterestsCsv = getContext() == null ? "" : getContext()
                                                        .getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE)
                                                        .getString("user_talking_points", "").trim();
                                                String[] reconciled = SkillMatchUtils.reconcileWithInterests(userInterestsCsv,
                                                        OpenAiClient.jsonArrayToCsv(result, "matching_skills"),
                                                        OpenAiClient.jsonArrayToCsv(result, "not_matching_skills"));
                                                if (!reconciled[0].isEmpty() || !reconciled[1].isEmpty()) {
                                                    current.setMatchingSkills(SkillMatchUtils.mergeSkillListExcluding(
                                                            current.getMatchingSkills(), reconciled[0], reconciled[1]));
                                                    current.setNotMatchingSkills(SkillMatchUtils.mergeSkillListExcluding(
                                                            current.getNotMatchingSkills(), reconciled[1], current.getMatchingSkills()));
                                                }
                                                dbHelper.updateJobCall(current);

                                                String noteToSave = !notesFromPoints.isEmpty()
                                                        ? notesFromPoints + " (AI Auto-transcribed)"
                                                        : (!sentimentComment.isEmpty()
                                                                ? "• " + sentimentComment + " (AI Auto-transcribed)"
                                                                : "• " + text.trim() + " (AI Auto-transcribed, raw)");
                                                if (!notesFromPoints.isEmpty() && !sentimentComment.isEmpty()) {
                                                    noteToSave = "• " + sentimentComment + "\n" + noteToSave;
                                                }
                                                dbHelper.insertNote(autoSaveJobId, noteToSave, System.currentTimeMillis(), DatabaseHelper.NOTE_SOURCE_MANUAL);

                                                etNotesField.setText("");
                                                if (etSched != null && !schedule.isEmpty()) {
                                                    etSched.setText(current.getTentativeSchedule());
                                                }
                                                if (spinRound != null) {
                                                    ArrayAdapter<CharSequence> adapter = (ArrayAdapter<CharSequence>) spinRound.getAdapter();
                                                    if (adapter != null) {
                                                        int pos = adapter.getPosition(current.getRoundStatus());
                                                        spinRound.setSelection(pos >= 0 ? pos : 0);
                                                    }
                                                }
                                                populateTimeline(timelineContainer, timelineLabel, autoSaveJobId);
                                                populateSkillsMatch(skillsSection, tvSkillsMatchingRef, tvSkillsNotMatchingRef, current);
                                                loadUpcomingInterviews();
                                                Toast.makeText(requireContext(), "Call logged automatically!", Toast.LENGTH_SHORT).show();
                                            }
                                        } else {
                                            Toast.makeText(requireContext(), "AI fields updated successfully!", Toast.LENGTH_SHORT).show();
                                        }
                                    } catch (Exception e) {
                                        activeDialogManualUploadAIFailed = true;
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
                                    activeDialogManualUploadAIFailed = true;
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
        String lower = val.toLowerCase();
        if (lower.equals("not mentioned") || lower.equals("not mentioned.") 
                || lower.equals("not_mentioned") || lower.equals("n/a") 
                || lower.equals("none") || lower.equals("unknown")) {
            return fallback;
        }
        return val;
    }

    private String copyUriToInternalStorage(android.net.Uri uri) {
        try {
            java.io.InputStream is = requireContext().getContentResolver().openInputStream(uri);
            if (is == null) return null;
            java.io.File dir = new java.io.File(requireContext().getFilesDir(), "jd_screenshots");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "jd_" + System.currentTimeMillis() + ".png");
            java.io.FileOutputStream os = new java.io.FileOutputStream(file);
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.close();
            is.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void showFullScreenImage(String path) {
        if (path == null || path.isEmpty()) return;
        android.app.Dialog dialog = new android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_full_screen_image);
        ImageView iv = dialog.findViewById(R.id.iv_full_screen);
        View btnClose = dialog.findViewById(R.id.btn_close_full_screen);
        
        iv.setImageURI(android.net.Uri.fromFile(new java.io.File(path)));
        
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }
        
        // Zoom and Pan Logic
        final float[] scaleFactor = {1.0f};
        final float[] lastTouchX = {0f};
        final float[] lastTouchY = {0f};
        final float[] posX = {0f};
        final float[] posY = {0f};
        final int[] activePointerId = {android.view.MotionEvent.INVALID_POINTER_ID};
        
        android.view.ScaleGestureDetector scaleDetector = new android.view.ScaleGestureDetector(requireContext(), 
            new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(android.view.ScaleGestureDetector detector) {
                    scaleFactor[0] *= detector.getScaleFactor();
                    scaleFactor[0] = Math.max(0.5f, Math.min(scaleFactor[0], 5.0f));
                    iv.setScaleX(scaleFactor[0]);
                    iv.setScaleY(scaleFactor[0]);
                    return true;
                }
            });
            
        iv.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            
            final int action = event.getActionMasked();
            switch (action) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    final int pointerIndex = event.getActionIndex();
                    final float x = event.getX(pointerIndex);
                    final float y = event.getY(pointerIndex);
                    lastTouchX[0] = x;
                    lastTouchY[0] = y;
                    activePointerId[0] = event.getPointerId(0);
                    break;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    final int pointerIndex = event.findPointerIndex(activePointerId[0]);
                    if (pointerIndex != android.view.MotionEvent.INVALID_POINTER_ID) {
                        final float x = event.getX(pointerIndex);
                        final float y = event.getY(pointerIndex);
                        
                        if (scaleFactor[0] > 1.0f) {
                            final float dx = x - lastTouchX[0];
                            final float dy = y - lastTouchY[0];
                            posX[0] += dx;
                            posY[0] += dy;
                            iv.setTranslationX(posX[0]);
                            iv.setTranslationY(posY[0]);
                        }
                    }
                    break;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    activePointerId[0] = android.view.MotionEvent.INVALID_POINTER_ID;
                    break;
                }
                case android.view.MotionEvent.ACTION_POINTER_UP: {
                    final int pointerIndex = event.getActionIndex();
                    final int pointerId = event.getPointerId(pointerIndex);
                    if (pointerId == activePointerId[0]) {
                        final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                        lastTouchX[0] = event.getX(newPointerIndex);
                        lastTouchY[0] = event.getY(newPointerIndex);
                        activePointerId[0] = event.getPointerId(newPointerIndex);
                    }
                    break;
                }
            }
            return true;
        });
        
        dialog.show();
    }

    private void updateScreenshotPreviews(View dialogView) {
        if (dialogView == null) return;
        for (int i = 0; i < 3; i++) {
            final int index = i;
            View container = dialogView.findViewById(containerIds[i]);
            ImageView preview = dialogView.findViewById(previewIds[index]);
            View removeBtn = dialogView.findViewById(removeIds[index]);

            if (container == null || preview == null || removeBtn == null) continue;

            String path = screenshotPaths[i];
            if (path != null && !path.trim().isEmpty()) {
                preview.setImageURI(android.net.Uri.fromFile(new java.io.File(path)));
                container.setVisibility(View.VISIBLE);
                
                preview.setOnClickListener(v -> showFullScreenImage(path));
                removeBtn.setOnClickListener(v -> {
                    screenshotPaths[index] = "";
                    updateScreenshotPreviews(dialogView);
                });
            } else {
                container.setVisibility(View.GONE);
            }
        }
    }

    private void handleJdScreenshotSelectedAtIndex(int index, android.net.Uri uri) {
        String path = copyUriToInternalStorage(uri);
        if (path != null) {
            screenshotPaths[index] = path;
            updateScreenshotPreviews(activeDialogView);
        } else {
            Toast.makeText(requireContext(), "Failed to process selected image.", Toast.LENGTH_SHORT).show();
        }
    }

    private void runAiOnManualNote(long jobId, long noteId, String noteText, Spinner spinnerRoundRef,
                                    EditText etScheduleRef, LinearLayout timelineContainer, View timelineLabel,
                                    View skillsSection, TextView tvSkillsMatchingRef, TextView tvSkillsNotMatchingRef) {
        String apiKey = requireContext().getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE)
                .getString("openai_api_key", "").trim();
        if (apiKey.isEmpty()) {
            Toast.makeText(requireContext(), "Note saved as-is - add an OpenAI API key in Settings to have it rewritten by AI.", Toast.LENGTH_LONG).show();
            return;
        }

        OpenAiClient.extractFields(requireContext(), noteText, new OpenAiClient.OpenAiCallback() {
            @Override
            public void onSuccess(JSONObject result) {
                if (dbHelper == null) return;
                try {
                    JobCall current = dbHelper.getJobCallById(jobId);
                    if (current == null) return;

                    String round = OpenAiClient.normalizeRoundStatus(
                            optClean(result, "present_round", ""), current.getRoundStatus());
                    boolean roundChanged = OpenAiClient.shouldUpdateRoundStatus(current.getRoundStatus(), round);
                    if (roundChanged) {
                        dbHelper.updateRoundStatus(jobId, round);
                    }

                    String schedule = optClean(result, "tentative_schedule", "");
                    if (!schedule.isEmpty()) {
                        current.setTentativeSchedule(schedule);
                        dbHelper.updateJobCall(current);
                    }

                    String rating = optClean(result, "interest_rating", "");
                    if (!rating.isEmpty()) {
                        current.setInterestRating(rating);
                        dbHelper.updateJobCall(current);
                    }

                    String sentimentComment = optClean(result, "sentiment_comment", "");
                    if (!sentimentComment.isEmpty()) {
                        dbHelper.insertNote(jobId, "• " + sentimentComment, System.currentTimeMillis());
                    }

                    boolean noteRewritten = false;
                    if (noteId > 0 && result.has("key_discussion_points")) {
                        org.json.JSONArray points = result.optJSONArray("key_discussion_points");
                        StringBuilder sb = new StringBuilder();
                        if (points != null) {
                            for (int i = 0; i < points.length(); i++) {
                                String pt = points.optString(i, "").trim();
                                if (!pt.isEmpty() && !pt.equalsIgnoreCase("null")) {
                                    if (sb.length() > 0) sb.append("\n");
                                    sb.append(pt.startsWith("•") || pt.startsWith("-") ? pt : "• " + pt);
                                }
                            }
                        }
                        String rewritten = sb.toString().trim();
                        if (!rewritten.isEmpty()) {
                            dbHelper.updateNoteText(noteId, jobId, rewritten);
                            noteRewritten = true;
                        }
                    }

                    String userInterestsCsv = getContext() == null ? "" : getContext()
                            .getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE)
                            .getString("user_talking_points", "").trim();
                    String[] reconciledSkills = SkillMatchUtils.reconcileWithInterests(userInterestsCsv,
                            OpenAiClient.jsonArrayToCsv(result, "matching_skills"),
                            OpenAiClient.jsonArrayToCsv(result, "not_matching_skills"));
                    String matchingSkills = reconciledSkills[0];
                    String notMatchingSkills = reconciledSkills[1];
                    boolean skillsChanged = !matchingSkills.isEmpty() || !notMatchingSkills.isEmpty();
                    if (skillsChanged) {
                        current.setMatchingSkills(SkillMatchUtils.mergeSkillListExcluding(
                                current.getMatchingSkills(), matchingSkills, notMatchingSkills));
                        current.setNotMatchingSkills(SkillMatchUtils.mergeSkillListExcluding(
                                current.getNotMatchingSkills(), notMatchingSkills, current.getMatchingSkills()));
                        dbHelper.updateJobCall(current);
                    }

                    if (isAdded() && getContext() != null) {
                        if (roundChanged && spinnerRoundRef != null) {
                            setSpinnerSelection(spinnerRoundRef, round);
                        }
                        if (!schedule.isEmpty() && etScheduleRef != null) {
                            etScheduleRef.setText(schedule);
                        }
                        if (!rating.isEmpty() && activeEtInterestRating != null) {
                            activeEtInterestRating.setText(rating);
                        }
                        if (!sentimentComment.isEmpty() || noteRewritten) {
                            populateTimeline(timelineContainer, timelineLabel, jobId);
                        }
                        if (skillsChanged) {
                            populateSkillsMatch(skillsSection, tvSkillsMatchingRef, tvSkillsNotMatchingRef, current);
                        }
                        loadUpcomingInterviews();
                    }
                } catch (Exception e) {
                    DebugLogger.log(requireContext(), "runAiOnManualNote failed: " + e.getMessage());
                }
            }

            @Override
            public void onError(String error) {
                DebugLogger.log(requireContext(), "runAiOnManualNote AI error: " + error);
            }
        });
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

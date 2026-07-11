package com.example.callsaver;

import android.Manifest;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.chip.Chip;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.widget.SpinnerAdapter;
import java.util.Calendar;
import org.json.JSONObject;
import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.io.File;
import java.util.Locale;

public class TrackerFragment extends Fragment implements JobCallAdapter.OnItemClickListener {

    private static final int ALL_PERMISSIONS_REQUEST_CODE = 200;

    private RecyclerView rvJobCalls;
    private View emptyStateLayout;

    private EditText activeEtCandidateName;
    private EditText activeEtCompany;
    private EditText activeEtAppliedRole;
    private EditText activeEtTentativeSchedule;
    private EditText activeEtNoticePeriod;
    private EditText activeEtMainAgenda;
    private EditText activeEtNotes;
    private EditText activeEtNextSteps;
    private Spinner activeSpinnerRound;
    private MaterialCardView cardPermissionsBanner;
    private FloatingActionButton fabAddCall;
    private EditText etSearch;

    private RecyclerView rvUpcomingInterviews;
    private View llUpcomingInterviewsContainer;
    private UpcomingInterviewsAdapter upcomingAdapter;
    private List<JobCall> upcomingList;

    private TextView tvStatLeads;
    private TextView tvStatScreenings;
    private TextView tvStatInterviews;
    private TextView tvStatOffers;

    private DatabaseHelper dbHelper;
    private static final int REQ_CODE_SPEECH_INPUT = 1001;
    private EditText activeDialogNotesField;
    private TextInputLayout activeDialogTilNotes;
    private JobCallAdapter adapter;
    private List<JobCall> callList; // Current filtered list bound to adapter
    private List<JobCall> allCallsList; // Master copy of all database calls

    private String searchQuery = "";
    private View cardLiveTranscribeStatus;
    private TextView tvLiveTranscribeMessage;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private String selectedStatus = "All";
    private TextView[] chips;
    private final String[] statuses = {"All", "Screening", "1st Round", "2nd Round", "Final Round", "HR / Salary", "Offered", "Rejected"};

    private final String[] requiredPermissions = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS
    };

    /**
     * Base permissions plus POST_NOTIFICATIONS on Android 13+ (needed for the
     * "save this caller" notification).
     */
    private String[] getRequiredPermissions() {
        List<String> perms = new ArrayList<>(Arrays.asList(requiredPermissions));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_tracker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new DatabaseHelper(requireContext());

        rvJobCalls = view.findViewById(R.id.rv_job_calls);
        emptyStateLayout = view.findViewById(R.id.empty_state_layout);
        cardPermissionsBanner = view.findViewById(R.id.card_permissions_banner);
        fabAddCall = view.findViewById(R.id.fab_add_call);
        etSearch = view.findViewById(R.id.et_search);

        tvStatLeads = view.findViewById(R.id.tv_stat_leads);
        tvStatScreenings = view.findViewById(R.id.tv_stat_screenings);
        tvStatInterviews = view.findViewById(R.id.tv_stat_interviews);
        tvStatOffers = view.findViewById(R.id.tv_stat_offers);

        // Setup RecyclerView
        rvJobCalls.setLayoutManager(new LinearLayoutManager(requireContext()));
        callList = new ArrayList<>();
        allCallsList = new ArrayList<>();
        adapter = new JobCallAdapter(requireContext(), callList, this);
        rvJobCalls.setAdapter(adapter);

        rvUpcomingInterviews = view.findViewById(R.id.rv_upcoming_interviews);
        llUpcomingInterviewsContainer = view.findViewById(R.id.ll_upcoming_interviews_container);

        if (rvUpcomingInterviews != null) {
            rvUpcomingInterviews.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            upcomingList = new ArrayList<>();
            upcomingAdapter = new UpcomingInterviewsAdapter(requireContext(), upcomingList, new UpcomingInterviewsAdapter.OnInterviewClickListener() {
                @Override
                public void onInterviewClick(JobCall call) {
                    showAddEditCallDialog(call);
                }
            });
            rvUpcomingInterviews.setAdapter(upcomingAdapter);
        }

        // Permissions banner click
        cardPermissionsBanner.setOnClickListener(v -> handlePermissionsRequestFlow());

        // Log call manually FAB click
        fabAddCall.setOnClickListener(v -> showAddEditCallDialog(null));

        // Open the full analytics dashboard from the stats card or the hint link
        View.OnClickListener openAnalytics = v ->
                startActivity(new Intent(requireContext(), AnalyticsActivity.class));
        View cardStats = view.findViewById(R.id.card_stats_dashboard);
        View tvAnalytics = view.findViewById(R.id.tv_view_analytics);
        if (cardStats != null) cardStats.setOnClickListener(openAnalytics);
        if (tvAnalytics != null) tvAnalytics.setOnClickListener(openAnalytics);


        // Setup filter chips
        setupFilterChips(view);

        // Setup Live Search
        setupSearchListener();

        // Fade the header smoothly as it collapses on scroll
        AppBarLayout appBar = view.findViewById(R.id.appbar_tracker);
        View header = view.findViewById(R.id.header_layout);
        if (appBar != null && header != null) {
            appBar.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {
                @Override
                public void onOffsetChanged(AppBarLayout bar, int verticalOffset) {
                    int h = header.getHeight();
                    if (h > 0) {
                        header.setAlpha(1f - Math.min(1f, (float) Math.abs(verticalOffset) / h));
                    }
                }
            });
        }

        cardLiveTranscribeStatus = view.findViewById(R.id.card_live_transcribe_status);
        tvLiveTranscribeMessage = view.findViewById(R.id.tv_live_transcribe_message);
        
        android.content.SharedPreferences trackerPrefs = requireContext().getSharedPreferences("CallSaverPrefs", android.content.Context.MODE_PRIVATE);
        prefsListener = new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(android.content.SharedPreferences sharedPreferences, String key) {
                if ("is_transcribing".equals(key) || "transcribing_number".equals(key)) {
                    updateLiveTranscriptionBanner();
                }
            }
        };
        trackerPrefs.registerOnSharedPreferenceChangeListener(prefsListener);
        updateLiveTranscriptionBanner();

        // Load logs
        refreshDashboardList();

        // Setup swipe gestures
        setupSwipeGestures();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkPermissionsBannerVisibility();
        refreshDashboardList();
        updateLiveTranscriptionBanner();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (prefsListener != null) {
            android.content.SharedPreferences trackerPrefs = requireContext().getSharedPreferences("CallSaverPrefs", android.content.Context.MODE_PRIVATE);
            trackerPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
    }

    private void updateLiveTranscriptionBanner() {
        if (isAdded()) {
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("CallSaverPrefs", android.content.Context.MODE_PRIVATE);
            boolean isTranscribing = prefs.getBoolean("is_transcribing", false);
            String transcribingNumber = prefs.getString("transcribing_number", "");

            if (isTranscribing && cardLiveTranscribeStatus != null && tvLiveTranscribeMessage != null) {
                cardLiveTranscribeStatus.setVisibility(View.VISIBLE);
                tvLiveTranscribeMessage.setText("⚡ Transcribing latest call with " + transcribingNumber + " via Deepgram...");
            } else if (cardLiveTranscribeStatus != null) {
                cardLiveTranscribeStatus.setVisibility(View.GONE);
            }
        }
    }

    private void setupSearchListener() {
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    searchQuery = s.toString().trim();
                    filterList(searchQuery, selectedStatus);
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
    }



    private void setupFilterChips(View view) {
        int[] chipIds = {
                R.id.chip_all, R.id.chip_screening, R.id.chip_1st_round,
                R.id.chip_2nd_round, R.id.chip_final, R.id.chip_hr,
                R.id.chip_offered, R.id.chip_rejected
        };

        chips = new TextView[chipIds.length];
        for (int i = 0; i < chipIds.length; i++) {
            final int index = i;
            chips[i] = view.findViewById(chipIds[i]);
            if (chips[i] != null) {
                chips[i].setOnClickListener(v -> {
                    selectedStatus = statuses[index];
                    updateChipsUI();
                    filterList(searchQuery, selectedStatus);
                });
            }
        }
        updateChipsUI();
    }

    private void updateChipsUI() {
        if (chips == null) return;
        float density = getResources().getDisplayMetrics().density;
        // Theme-aware colors so chips adapt correctly in light & dark mode.
        int selectedColor = ContextCompat.getColor(requireContext(), R.color.accent_indigo);
        int unselectedBg = ContextCompat.getColor(requireContext(), R.color.divider);
        int unselectedText = ContextCompat.getColor(requireContext(), R.color.text_secondary);
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;

            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.RECTANGLE);
            drawable.setCornerRadius(18 * density); // Pill shape

            if (statuses[i].equals(selectedStatus)) {
                // Selected: Accent indigo background, white text
                drawable.setColor(selectedColor);
                chips[i].setBackground(drawable);
                chips[i].setTextColor(Color.WHITE);
            } else {
                // Deselected: Muted background + secondary text (adapts to night mode)
                drawable.setColor(unselectedBg);
                chips[i].setBackground(drawable);
                chips[i].setTextColor(unselectedText);
            }
        }
    }

    private void filterList(String query, String status) {
        List<JobCall> filteredList = new ArrayList<>();
        for (JobCall call : allCallsList) {
            boolean matchesQuery = query.isEmpty() ||
                    (call.getCompanyName() != null && call.getCompanyName().toLowerCase().contains(query.toLowerCase())) ||
                    (call.getTags() != null && call.getTags().toLowerCase().contains(query.toLowerCase())) ||
                    (call.getPhoneNumber() != null && call.getPhoneNumber().contains(query)) ||
                    (call.getNotes() != null && call.getNotes().toLowerCase().contains(query.toLowerCase()));
            
            boolean matchesStatus = status.equals("All") ||
                    (call.getRoundStatus() != null && call.getRoundStatus().equals(status));

            if (matchesQuery && matchesStatus) {
                filteredList.add(call);
            }
        }

        callList.clear();
        callList.addAll(filteredList);
        adapter.notifyDataSetChanged();

        if (callList.isEmpty()) {
            emptyStateLayout.setVisibility(View.VISIBLE);
            rvJobCalls.setVisibility(View.GONE);
        } else {
            emptyStateLayout.setVisibility(View.GONE);
            rvJobCalls.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Refreshes the calls displayed on the RecyclerView from the SQLite DB.
     */
    public void refreshDashboardList() {
        if (dbHelper == null) return;
        List<JobCall> updatedCalls = dbHelper.getAllJobCalls();
        allCallsList.clear();
        allCallsList.addAll(updatedCalls);

        // Calculate Statistics
        int leads = allCallsList.size();
        int screenings = 0;
        int interviews = 0;
        int offers = 0;
        for (JobCall c : allCallsList) {
            String st = c.getRoundStatus();
            if (st == null) continue;
            if (st.equals("Screening") || st.equals("HR / Salary")) {
                screenings++;
            } else if (st.equals("1st Round") || st.equals("2nd Round") || st.equals("Final Round")) {
                interviews++;
            } else if (st.equals("Offered")) {
                offers++;
            }
        }
        
        if (tvStatLeads != null) tvStatLeads.setText(String.valueOf(leads));
        if (tvStatScreenings != null) tvStatScreenings.setText(String.valueOf(screenings));
        if (tvStatInterviews != null) tvStatInterviews.setText(String.valueOf(interviews));
        if (tvStatOffers != null) tvStatOffers.setText(String.valueOf(offers));

        // Filter and update Upcoming Interviews deck
        if (upcomingList != null) {
            List<JobCall> activeInterviews = new ArrayList<>();
            for (JobCall c : allCallsList) {
                String status = c.getRoundStatus();
                if (status != null && (status.equals("Rejected") || status.equals("Offered"))) {
                    continue;
                }
                
                String schedule = c.getTentativeSchedule();
                if (schedule != null && !schedule.trim().isEmpty()) {
                    activeInterviews.add(c);
                } else if (status != null && (status.equals("Screening") || status.equals("1st Round") || status.equals("2nd Round") || status.equals("Final Round") || status.equals("HR / Salary"))) {
                    activeInterviews.add(c);
                }
            }
            upcomingList.clear();
            upcomingList.addAll(activeInterviews);
            if (upcomingAdapter != null) {
                upcomingAdapter.notifyDataSetChanged();
            }
            if (llUpcomingInterviewsContainer != null) {
                if (upcomingList.isEmpty()) {
                    llUpcomingInterviewsContainer.setVisibility(View.GONE);
                } else {
                    llUpcomingInterviewsContainer.setVisibility(View.VISIBLE);
                }
            }
        }

        filterList(searchQuery, selectedStatus);
    }

    /**
     * Checks if all required permissions are granted and updates the banner visibility.
     */
    private void checkPermissionsBannerVisibility() {
        if (getActivity() == null) return;
        boolean allGranted = true;
        for (String perm : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        boolean overlayGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            overlayGranted = Settings.canDrawOverlays(requireContext());
        }

        if (allGranted && overlayGranted) {
            cardPermissionsBanner.setVisibility(View.GONE);
        } else {
            cardPermissionsBanner.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Triggers permission requests or opens overlay configuration settings.
     */
    private void handlePermissionsRequestFlow() {
        if (getActivity() == null) return;
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String perm : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(perm);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(),
                    listPermissionsNeeded.toArray(new String[0]),
                    ALL_PERMISSIONS_REQUEST_CODE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
            Toast.makeText(requireContext(), "Please enable 'Display over other apps' to allow popup logs.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        }
    }

    @Override
    public void onItemClick(JobCall jobCall) {
        showAddEditCallDialog(jobCall);
    }

    @Override
    public void onFollowUpClick(JobCall call) {
        if (getContext() == null) return;
        
        String[] options = {"Share via WhatsApp", "Draft Email"};
        new AlertDialog.Builder(requireContext())
                .setTitle("Follow up with recruiter")
                .setItems(options, (dialog, which) -> {
                    String recruiterName = call.getRecruiterName() != null && !call.getRecruiterName().isEmpty() ? call.getRecruiterName() : "Recruiter";
                    String companyName = call.getCompanyName() != null && !call.getCompanyName().isEmpty() ? call.getCompanyName() : "Company";
                    String role = call.getAppliedRole() != null && !call.getAppliedRole().isEmpty() ? call.getAppliedRole() : "Developer Role";
                    String schedule = call.getTentativeSchedule() != null && !call.getTentativeSchedule().isEmpty() ? call.getTentativeSchedule() : "our next meeting";
                    
                    String message = String.format("Hi %s,\n\nThanks for discussing the %s position at %s today. As agreed, here is my resume.\n\nLooking forward to %s.\n\nBest regards,\nVinay",
                            recruiterName, role, companyName, schedule.contains("next") || schedule.contains("tomorrow") || schedule.contains("at") ? "our next call on " + schedule : "our next call");
                    
                    if (which == 0) {
                        // WhatsApp
                        try {
                            Intent whatsappIntent = new Intent(Intent.ACTION_SEND);
                            whatsappIntent.setType("text/plain");
                            whatsappIntent.putExtra(Intent.EXTRA_TEXT, message);
                            whatsappIntent.setPackage("com.whatsapp");
                            startActivity(whatsappIntent);
                        } catch (Exception e) {
                            Intent intent = new Intent(Intent.ACTION_SEND);
                            intent.setType("text/plain");
                            intent.putExtra(Intent.EXTRA_TEXT, message);
                            startActivity(Intent.createChooser(intent, "Share via"));
                        }
                    } else {
                        // Email
                        Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
                        emailIntent.setData(Uri.parse("mailto:"));
                        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Follow-up: " + role + " at " + companyName);
                        emailIntent.putExtra(Intent.EXTRA_TEXT, message);
                        try {
                            startActivity(Intent.createChooser(emailIntent, "Send email..."));
                        } catch (Exception e) {
                            Toast.makeText(requireContext(), "No email app installed", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Opens the modal dialog to either Log a manual call (if call is null)
     * or edit/delete an existing logged call (if call is not null).
     */
    public void showAddEditCallDialog(final JobCall editCall) {
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
        activeDialogTilNotes = tilNotes;

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

        // Bind Spinner choices
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.round_statuses, android.R.layout.simple_spinner_item);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRound.setAdapter(spinnerAdapter);

        final AlertDialog alertDialog = builder.create();
        alertDialog.setOnDismissListener(dialog -> {
            activeDialogNotesField = null;
            activeDialogTilNotes = null;
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

        // Configure Dialog Mode (Edit vs. Add)
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

            // Calls + notes are shown as a merged timeline below; the field adds a new note.
            populateTimeline(llNotesTimeline, labelNotes, editCall.getId());
            btnSave.setText(R.string.btn_update);
            btnDelete.setVisibility(View.VISIBLE);
            btnReminder.setVisibility(View.VISIBLE);

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
            btnReminder.setVisibility(View.GONE);
            if (editCall != null) {
                etPhone.setText(editCall.getPhoneNumber());
                
                // Auto pre-fill if call is already tracked in SQLite
                JobCall existingCall = dbHelper.getJobCallByNumber(requireContext(), editCall.getPhoneNumber());
                if (existingCall != null) {
                    etCompany.setText(existingCall.getCompanyName());
                    etTags.setText(existingCall.getTags());
                    if (existingCall.getRoundStatus() != null) {
                        int position = spinnerAdapter.getPosition(existingCall.getRoundStatus());
                        spinnerRound.setSelection(position >= 0 ? position : 0);
                    }
                    etCandidateName.setText(existingCall.getCandidateName());
                    etAppliedRole.setText(existingCall.getAppliedRole());
                    etTentativeSchedule.setText(existingCall.getTentativeSchedule());
                    etNoticePeriod.setText(existingCall.getNoticePeriod());
                    etMainAgenda.setText(existingCall.getMainAgenda());
                    etNextSteps.setText(existingCall.getNextSteps());
                }
            }
        }

        btnCancel.setOnClickListener(v -> alertDialog.dismiss());

        // Save directly to contacts click action
        btnSaveContacts.setOnClickListener(v -> {
            String company = etCompany.getText().toString().trim();
            if (company.isEmpty()) {
                Toast.makeText(requireContext(), R.string.msg_company_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (editCall != null && saveContactDirectly(company, editCall.getPhoneNumber())) {
                Toast.makeText(requireContext(), "Saved to phone contacts!", Toast.LENGTH_SHORT).show();
                btnSaveContacts.setVisibility(View.GONE);
            } else {
                Toast.makeText(requireContext(), "Failed to save contact.", Toast.LENGTH_SHORT).show();
            }
        });

        // Schedule Follow-up Reminder click action
        btnReminder.setOnClickListener(v -> {
            try {
                Intent calendarIntent = new Intent(Intent.ACTION_INSERT)
                        .setData(Uri.parse("content://com.android.calendar/events"))
                        .putExtra("title", "Follow up: " + (editCall != null ? editCall.getCompanyName() : ""))
                        .putExtra("description", "Follow-up reminder reminder for " + (editCall != null ? editCall.getCompanyName() : "") + "\nStage: " + (editCall != null ? editCall.getRoundStatus() : "") + "\nNotes: " + (editCall != null ? editCall.getNotes() : ""))
                        .putExtra("beginTime", System.currentTimeMillis() + 3 * 24 * 60 * 60 * 1000L) // Default 3 days from now
                        .putExtra("endTime", System.currentTimeMillis() + 3 * 24 * 60 * 60 * 1000L + 30 * 60 * 1000L);
                startActivity(calendarIntent);
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Could not open Calendar app", Toast.LENGTH_SHORT).show();
            }
        });

        // Delete click action
        btnDelete.setOnClickListener(v -> {
            if (editCall != null) {
                dbHelper.deleteJobCall(editCall.getId());
                Toast.makeText(requireContext(), R.string.msg_deleted, Toast.LENGTH_SHORT).show();
                refreshDashboardList();
                alertDialog.dismiss();
            }
        });

        // Save / Update click action
        btnSave.setOnClickListener(v -> {
            String phone = etPhone.getText().toString().trim();
            String company = etCompany.getText().toString().trim();
            String recruiter = etRecruiter.getText().toString().trim();
            String tags = etTags.getText().toString().trim();
            String noteToAdd = etNotes != null ? etNotes.getText().toString().trim() : "";
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
                // Update mode
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
                // Insert mode
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

            refreshDashboardList();
            alertDialog.dismiss();
        });

        // Apply rounded corner frame to dialog window
        if (alertDialog.getWindow() != null) {
            alertDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        alertDialog.show();

        // Wrap custom background shape on layout container inside alertDialog window
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

    /**
     * Fills the edit dialog's timeline with a merged, chronological list of calls
     * (in/out/missed + duration) and dated notes. Notes are individually deletable.
     */
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

    private boolean isContactExists(String number) {
        if (number == null || number.isEmpty()) {
            return false;
        }
        try {
            Uri lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number)
            );
            String[] projection = { ContactsContract.PhoneLookup._ID };
            try (Cursor cursor = requireContext().getContentResolver().query(lookupUri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean saveContactDirectly(String name, String phoneNumber) {
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE);
        String accName = prefs.getString("preferred_contact_account_name", null);
        String accType = prefs.getString("preferred_contact_account_type", null);

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        int rawContactInsertIndex = ops.size();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accType)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accName)
                .build());

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build());

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build());
        try {
            requireContext().getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Configures swipe gestures for the RecyclerView items:
     * - Swipe LEFT: Edit the log (opens edit dialog).
     * - Swipe RIGHT: Delete the log (shows premium confirmation dialog).
     */
    private void setupSwipeGestures() {
        ItemTouchHelper.SimpleCallback simpleCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                if (position < 0 || position >= callList.size()) return;
                JobCall jobCall = callList.get(position);

                if (direction == ItemTouchHelper.LEFT) {
                    // Swipe Left: Edit
                    showAddEditCallDialog(jobCall);
                    adapter.notifyItemChanged(position);
                } else if (direction == ItemTouchHelper.RIGHT) {
                    // Swipe Right: Delete
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Delete Call Log")
                            .setMessage("Are you sure you want to delete the log for " + jobCall.getCompanyName() + "?")
                            .setPositiveButton("Delete", (dialog, which) -> {
                                dbHelper.deleteJobCall(jobCall.getId());
                                Toast.makeText(requireContext(), "Log deleted", Toast.LENGTH_SHORT).show();
                                refreshDashboardList();
                            })
                            .setNegativeButton("Cancel", (dialog, which) -> {
                                adapter.notifyItemChanged(position);
                            })
                            .setOnCancelListener(dialog -> {
                                adapter.notifyItemChanged(position);
                            })
                            .show();
                }
            }
        };

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleCallback);
        itemTouchHelper.attachToRecyclerView(rvJobCalls);
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

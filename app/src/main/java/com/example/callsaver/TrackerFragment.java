package com.example.callsaver;

import android.Manifest;
import android.content.ContentProviderOperation;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TrackerFragment extends Fragment implements JobCallAdapter.OnItemClickListener {

    private static final int ALL_PERMISSIONS_REQUEST_CODE = 200;

    private RecyclerView rvJobCalls;
    private View emptyStateLayout;
    private MaterialCardView cardPermissionsBanner;
    private FloatingActionButton fabAddCall;
    private EditText etSearch;

    private TextView tvStatLeads;
    private TextView tvStatScreenings;
    private TextView tvStatInterviews;
    private TextView tvStatOffers;

    private DatabaseHelper dbHelper;
    private static final int REQ_RECORD_AUDIO = 2001;
    private VoiceNoteHelper voiceNoteHelper;
    private EditText activeDialogNotesField;
    private Chip activeDialogAiChip;
    private TextInputLayout activeDialogTilNotes;
    private JobCallAdapter adapter;
    private List<JobCall> callList; // Current filtered list bound to adapter
    private List<JobCall> allCallsList; // Master copy of all database calls

    private String searchQuery = "";
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
        EditText etTags = dialogView.findViewById(R.id.et_tags);
        EditText etNotes = dialogView.findViewById(R.id.et_notes);
        LinearLayout llNotesTimeline = dialogView.findViewById(R.id.ll_notes_timeline);
        View labelNotes = dialogView.findViewById(R.id.label_notes);
        Spinner spinnerRound = dialogView.findViewById(R.id.spinner_round);

        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_dialog_save);
        Button btnDelete = dialogView.findViewById(R.id.btn_dialog_delete);
        Button btnSaveContacts = dialogView.findViewById(R.id.btn_dialog_save_contacts);
        Button btnReminder = dialogView.findViewById(R.id.btn_dialog_reminder);

        TextInputLayout tilNotes = dialogView.findViewById(R.id.til_notes);
        Chip chipAiPolish = dialogView.findViewById(R.id.chip_ai_polish);

        initVoiceHelper();
        activeDialogNotesField = etNotes;
        activeDialogAiChip = chipAiPolish;
        activeDialogTilNotes = tilNotes;

        etNotes.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (s.toString().trim().isEmpty()) {
                    chipAiPolish.setVisibility(View.GONE);
                } else {
                    if (voiceNoteHelper == null || !voiceNoteHelper.isListening()) {
                        chipAiPolish.setVisibility(View.VISIBLE);
                    }
                }
            }
        });

        tilNotes.setEndIconOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                toggleVoiceNote();
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            }
        });

        chipAiPolish.setOnClickListener(v -> {
            String apiKey = OpenAiHelper.getApiKey(requireContext());
            if (apiKey == null || apiKey.isEmpty()) {
                OpenAiHelper.showApiKeyDialog(requireContext(), this::runAiPolish);
            } else {
                runAiPolish();
            }
        });

        // Bind Spinner choices
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.round_statuses, android.R.layout.simple_spinner_item);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRound.setAdapter(spinnerAdapter);

        final AlertDialog alertDialog = builder.create();
        alertDialog.setOnDismissListener(dialog -> {
            if (voiceNoteHelper != null) {
                voiceNoteHelper.stopListening();
            }
            activeDialogNotesField = null;
            activeDialogAiChip = null;
            activeDialogTilNotes = null;
        });

        // Configure Dialog Mode (Edit vs. Add)
        if (editCall != null && editCall.getId() > 0) {
            dialogTitle.setText(R.string.title_edit_job_call);
            etPhone.setText(editCall.getPhoneNumber());
            etCompany.setText(editCall.getCompanyName());
            etTags.setText(editCall.getTags());
            // Calls + notes are shown as a merged timeline below; the field adds a new note.
            populateTimeline(llNotesTimeline, labelNotes, editCall.getId());
            btnSave.setText(R.string.btn_update);
            btnDelete.setVisibility(View.VISIBLE);
            btnReminder.setVisibility(View.VISIBLE);

            // Show/hide 'Save to Contacts' button based on existence
            if (isContactExists(editCall.getPhoneNumber())) {
                btnSaveContacts.setVisibility(View.GONE);
            } else {
                btnSaveContacts.setVisibility(View.VISIBLE);
            }

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
            String tags = etTags.getText().toString().trim();
            String noteToAdd = etNotes != null ? etNotes.getText().toString().trim() : "";
            String round = spinnerRound.getSelectedItem().toString();

            if (phone.isEmpty()) {
                Toast.makeText(requireContext(), R.string.msg_phone_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (company.isEmpty()) {
                Toast.makeText(requireContext(), R.string.msg_company_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            if (editCall != null && editCall.getId() > 0) {
                // Update mode
                editCall.setPhoneNumber(phone);
                editCall.setCompanyName(company);
                editCall.setTags(tags);
                editCall.setRoundStatus(round);

                dbHelper.updateJobCall(editCall);
                if (!noteToAdd.isEmpty()) {
                    dbHelper.insertNote(editCall.getId(), noteToAdd, System.currentTimeMillis());
                }
                Toast.makeText(requireContext(), "Log updated!", Toast.LENGTH_SHORT).show();
            } else {
                // Insert mode
                JobCall newCall = new JobCall(phone, company, round, tags, "", 0, System.currentTimeMillis());
                long newId = dbHelper.insertJobCall(newCall);
                if (newId != -1 && !noteToAdd.isEmpty()) {
                    dbHelper.insertNote(newId, noteToAdd, System.currentTimeMillis());
                }

                // Always write to phone contacts address book
                boolean contactSaved = saveContactDirectly(company, phone);
                if (contactSaved) {
                    Toast.makeText(requireContext(), "Logged and saved to phone contacts!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), "Logged, but failed to save to contacts. Check permissions.", Toast.LENGTH_LONG).show();
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

    private void initVoiceHelper() {
        if (voiceNoteHelper == null && getContext() != null) {
            voiceNoteHelper = new VoiceNoteHelper(requireContext(), new VoiceNoteHelper.VoiceCallback() {
                @Override
                public void onTextReceived(String text) {
                    if (activeDialogNotesField != null) {
                        String current = activeDialogNotesField.getText().toString();
                        if (current.trim().isEmpty()) {
                            activeDialogNotesField.setText(text);
                        } else {
                            activeDialogNotesField.setText(current + " " + text);
                        }
                        activeDialogNotesField.setSelection(activeDialogNotesField.getText().length());
                    }
                }

                @Override
                public void onRecordingStateChanged(boolean isRecording) {
                    if (activeDialogTilNotes != null) {
                        if (isRecording) {
                            activeDialogTilNotes.setEndIconTintList(android.content.res.ColorStateList.valueOf(
                                    ContextCompat.getColor(requireContext(), R.color.status_error))); // red
                            if (activeDialogAiChip != null) {
                                activeDialogAiChip.setVisibility(View.GONE);
                            }
                        } else {
                            activeDialogTilNotes.setEndIconTintList(android.content.res.ColorStateList.valueOf(
                                    ContextCompat.getColor(requireContext(), R.color.accent_indigo))); // active indigo
                            if (activeDialogAiChip != null && activeDialogNotesField != null
                                    && !activeDialogNotesField.getText().toString().trim().isEmpty()) {
                                activeDialogAiChip.setVisibility(View.VISIBLE);
                            }
                        }
                    }
                }

                @Override
                public void onError(String errorMessage) {
                    if (getContext() != null) {
                        Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    private void toggleVoiceNote() {
        if (voiceNoteHelper != null) {
            if (voiceNoteHelper.isListening()) {
                voiceNoteHelper.stopListening();
            } else {
                voiceNoteHelper.startListening();
            }
        }
    }

    private void runAiPolish() {
        if (activeDialogNotesField == null || activeDialogAiChip == null) return;
        String currentText = activeDialogNotesField.getText().toString().trim();
        if (currentText.isEmpty()) return;

        activeDialogAiChip.setEnabled(false);
        activeDialogAiChip.setText("✨ Polishing...");

        OpenAiHelper.polishNotes(requireContext(), currentText, new OpenAiHelper.PolishCallback() {
            @Override
            public void onSuccess(String polishedText) {
                if (activeDialogNotesField != null) {
                    activeDialogNotesField.setText(polishedText);
                    activeDialogNotesField.setSelection(polishedText.length());
                }
                if (activeDialogAiChip != null) {
                    activeDialogAiChip.setEnabled(true);
                    activeDialogAiChip.setText("✨ Polish with AI");
                }
                if (getContext() != null) {
                    Toast.makeText(requireContext(), "Notes polished with AI!", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String errorMessage) {
                if (activeDialogAiChip != null) {
                    activeDialogAiChip.setEnabled(true);
                    activeDialogAiChip.setText("✨ Polish with AI");
                }
                if (getContext() != null) {
                    Toast.makeText(requireContext(), "Error: " + errorMessage, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleVoiceNote();
            } else {
                if (getContext() != null) {
                    Toast.makeText(requireContext(), "Permission denied to record audio", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onDestroyView() {
        if (voiceNoteHelper != null) {
            voiceNoteHelper.destroy();
            voiceNoteHelper = null;
        }
        super.onDestroyView();
    }
}

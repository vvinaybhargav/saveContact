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
import android.provider.CallLog;
import android.provider.Settings;
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
import java.util.HashSet;
import java.util.List;
import java.io.File;
import java.util.Locale;
import java.util.Set;

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
    private EditText activeEtInterestRating;
    private Spinner activeSpinnerRound;
    private View activeLlTranscriptionProgress;
    private TextView activeTvTranscriptionStatus;
    // True once the user manually uploads/transcribes a recording during this dialog
    // session; the note saved on this Save click is tagged "manual" (shown as "MCall").
    private boolean activeDialogManualUploadUsed;
    private boolean activeDialogManualUploadAIFailed;
    private TextView tvCheckDuplicates;
    private MaterialCardView cardPermissionsBanner;
    private FloatingActionButton fabAddCall;
    private EditText etSearch;



    private TextView tvStatLeads;
    private TextView tvStatScreenings;
    private TextView tvStatInterviews;
    private TextView tvStatOffers;

    private DatabaseHelper dbHelper;
    private static final int REQ_CODE_SPEECH_INPUT = 1001;
    private static final int REQ_CODE_PICK_JD_SCREENSHOT = 800;
    private String currentJdImagePath = null;
    private FrameLayout activeFlJdPreviewContainer;
    private ImageView activeIvJdPreview;

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
    private final String[] statuses = {"All", "First time", "1st Round", "2nd Round", "Final Round", "HR / Salary", "Offered", "Not Interested", "Negative"};

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

        tvCheckDuplicates = view.findViewById(R.id.tv_check_duplicates);
        if (tvCheckDuplicates != null) {
            tvCheckDuplicates.setOnClickListener(v -> showDuplicateReviewDialog());
        }


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
                R.id.chip_offered, R.id.chip_not_interested, R.id.chip_rejected
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

    public static String getContactNameByNumber(Context context, String phoneNumber) {
        if (context == null || phoneNumber == null || phoneNumber.isEmpty()) {
            return null;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        String[] projection = new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private List<JobCall> getUnloggedCallLogs() {
        List<JobCall> unlogged = new ArrayList<>();
        if (getContext() == null) return unlogged;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return unlogged;
        }

        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);
        java.util.Set<String> dismissed = prefs.getStringSet("dismissed_unlogged_calls", new java.util.HashSet<>());

        String[] projection = new String[]{
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
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
                int typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE);
                int dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE);
                int durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION);

                int count = 0;
                do {
                    String number = cursor.getString(numberIdx);
                    int type = cursor.getInt(typeIdx);
                    long date = cursor.getLong(dateIdx);
                    int duration = cursor.getInt(durationIdx);

                    // Check if already tracked in SQLite
                    JobCall existing = dbHelper.getJobCallByNumber(requireContext(), number);
                    if (existing == null) {
                        // Check if dismissed
                        String key = number + "_" + date;
                        if (!dismissed.contains(key)) {
                            String badgeType = "Answered";
                            String notesDesc = "Call";
                            if (type == CallLog.Calls.MISSED_TYPE) {
                                badgeType = "Missed Call";
                                notesDesc = "Missed Call";
                            } else if (type == CallLog.Calls.REJECTED_TYPE) {
                                badgeType = "Rejected";
                                notesDesc = "Rejected Call";
                            } else if (type == CallLog.Calls.INCOMING_TYPE) {
                                badgeType = "Answered";
                                notesDesc = "Incoming Call";
                            } else if (type == CallLog.Calls.OUTGOING_TYPE) {
                                badgeType = "Answered";
                                notesDesc = "Outgoing Call";
                            }

                            String displayName = getContactNameByNumber(requireContext(), number);
                            if (displayName == null || displayName.trim().isEmpty()) {
                                displayName = "Unlogged Call";
                            }

                            JobCall unloggedCall = new JobCall(number, displayName, badgeType, "", notesDesc, duration, date);
                            unloggedCall.setId((int) (-1 * (Math.abs(key.hashCode()) % 1000000 + 1)));
                            unlogged.add(unloggedCall);
                        }
                    }
                    count++;
                } while (cursor.moveToNext() && count < 40);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return unlogged;
    }

    /**
     * Refreshes the calls displayed on the RecyclerView from the SQLite DB.
     */
    public void refreshDashboardList() {
        if (dbHelper == null) return;
        List<JobCall> updatedCalls = dbHelper.getAllJobCallsSortedByRecentActivity();
        
        List<JobCall> unloggedCalls = getUnloggedCallLogs();
        
        allCallsList.clear();
        allCallsList.addAll(updatedCalls);
        allCallsList.addAll(unloggedCalls);

        // Sort allCallsList by timestamp DESC
        java.util.Collections.sort(allCallsList, (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));

        // Calculate Statistics using only tracked calls (positive ID)
        int leads = 0;
        int screenings = 0;
        int interviews = 0;
        int offers = 0;
        for (JobCall c : allCallsList) {
            if (c.getId() <= 0) continue; // skip unlogged calls for stats
            leads++;
            String st = c.getRoundStatus();
            if (st == null) continue;
            if (st.equals("First time") || st.equals("HR / Salary")) {
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

        refreshDuplicateSuggestionVisibility();

        filterList(searchQuery, selectedStatus);
    }

    /**
     * Runs the local (free, offline) duplicate-company heuristic in the background and
     * shows/hides the "Check for duplicate companies" link depending on whether any
     * candidates survive after excluding pairs the user already dismissed.
     */
    private void refreshDuplicateSuggestionVisibility() {
        if (tvCheckDuplicates == null || dbHelper == null) return;
        List<JobCall> snapshot = new ArrayList<>(allCallsList);
        new Thread(() -> {
            Set<String> dismissed = getDismissedDuplicatePairs();
            List<DuplicateDetector.Candidate> found = DuplicateDetector.findDuplicates(snapshot, dismissed);
            if (getActivity() == null || !isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (tvCheckDuplicates != null) {
                    tvCheckDuplicates.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
                }
            });
        }).start();
    }

    private Set<String> getDismissedDuplicatePairs() {
        String raw = requireContext().getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE)
                .getString("dismissed_duplicate_pairs", "");
        Set<String> set = new HashSet<>();
        if (!raw.trim().isEmpty()) {
            for (String key : raw.split(",")) {
                if (!key.trim().isEmpty()) set.add(key.trim());
            }
        }
        return set;
    }

    private void addDismissedDuplicatePair(String pairKey) {
        Set<String> set = getDismissedDuplicatePairs();
        set.add(pairKey);
        String joined = android.text.TextUtils.join(",", set);
        requireContext().getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE)
                .edit().putString("dismissed_duplicate_pairs", joined).apply();
    }

    /**
     * Shows every suggested duplicate-company pair with Merge / Not a duplicate actions.
     * Merge consolidates both entries (notes, call history, phone numbers) into one via
     * DatabaseHelper.mergeJobCalls; "Not a duplicate" remembers the pair so it stops
     * being suggested.
     */
    private void showDuplicateReviewDialog() {
        List<DuplicateDetector.Candidate> candidates =
                DuplicateDetector.findDuplicates(allCallsList, getDismissedDuplicatePairs());
        if (candidates.isEmpty()) {
            Toast.makeText(requireContext(), "No duplicate companies found.", Toast.LENGTH_SHORT).show();
            if (tvCheckDuplicates != null) tvCheckDuplicates.setVisibility(View.GONE);
            return;
        }

        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(container);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Possible duplicate companies")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        LayoutInflater inflater = getLayoutInflater();
        for (DuplicateDetector.Candidate cand : candidates) {
            View row = inflater.inflate(R.layout.item_duplicate_pair, container, false);
            ((TextView) row.findViewById(R.id.tv_dup_score)).setText(cand.scorePercent + "% similar");
            ((TextView) row.findViewById(R.id.tv_dup_name_a)).setText(cand.a.getCompanyName());
            ((TextView) row.findViewById(R.id.tv_dup_name_b)).setText(cand.b.getCompanyName());

            row.findViewById(R.id.btn_dup_dismiss).setOnClickListener(v -> {
                addDismissedDuplicatePair(DuplicateDetector.pairKey(cand.a.getId(), cand.b.getId()));
                container.removeView(row);
                if (container.getChildCount() == 0) {
                    dialog.dismiss();
                    refreshDuplicateSuggestionVisibility();
                }
            });

            row.findViewById(R.id.btn_dup_merge).setOnClickListener(v -> {
                // Keep the entry with more history (older/first-seen), fold the other into it.
                JobCall keep = cand.a.getTimestamp() <= cand.b.getTimestamp() ? cand.a : cand.b;
                JobCall loser = keep == cand.a ? cand.b : cand.a;
                dbHelper.mergeJobCalls(keep.getId(), loser.getId());
                Toast.makeText(requireContext(),
                        "Merged \"" + loser.getCompanyName() + "\" into \"" + keep.getCompanyName() + "\"",
                        Toast.LENGTH_SHORT).show();
                container.removeView(row);
                refreshDashboardList();
                if (container.getChildCount() == 0) {
                    dialog.dismiss();
                }
            });

            container.addView(row);
        }

        dialog.show();
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

        activeDialogManualUploadUsed = false;
        activeDialogManualUploadAIFailed = false;

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
        EditText etNoticePeriod = null; // Notice Period removed from the UI; preserved in DB if already set.
        // Main Agenda / Next Steps removed from the UI; preserved in DB if already set.
        EditText etMainAgenda = null;
        EditText etNextSteps = null;
        EditText etInterestRating = dialogView.findViewById(R.id.et_interest_rating);

        activeEtCandidateName = etCandidateName;
        activeEtCompany = etCompany;
        activeEtAppliedRole = etAppliedRole;
        activeEtTentativeSchedule = etTentativeSchedule;
        activeEtNoticePeriod = etNoticePeriod;
        activeEtMainAgenda = etMainAgenda;
        activeEtNotes = etNotes;
        activeEtNextSteps = etNextSteps;
        activeEtInterestRating = etInterestRating;
        activeSpinnerRound = spinnerRound;
        activeLlTranscriptionProgress = dialogView.findViewById(R.id.ll_dialog_transcription_progress);
        activeTvTranscriptionStatus = dialogView.findViewById(R.id.tv_dialog_transcription_status);

        // The AI already resolves relative phrases like "tomorrow at 2pm" heard in the
        // call into an absolute date/time (see OpenAiClient); manual entry uses a picker.
        if (etTentativeSchedule != null) {
            etTentativeSchedule.setOnClickListener(v -> showDateTimePicker(etTentativeSchedule));
        }

        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_dialog_save);
        Button btnDelete = dialogView.findViewById(R.id.btn_dialog_delete);
        Button btnSaveContacts = dialogView.findViewById(R.id.btn_dialog_save_contacts);
        Button btnShowBanner = dialogView.findViewById(R.id.btn_dialog_show_banner);

        TextInputLayout tilNotes = dialogView.findViewById(R.id.til_notes);

        activeDialogNotesField = etNotes;
        activeDialogTilNotes = tilNotes;

        View tvDialogManualRecording = dialogView.findViewById(R.id.tv_dialog_manual_recording);
        if (tvDialogManualRecording != null) {
            Long autoSaveJobId = (editCall != null && editCall.getId() > 0) ? (long) editCall.getId() : null;
            tvDialogManualRecording.setOnClickListener(v -> showManualRecordingDialogForNotesField(etNotes, autoSaveJobId,
                    llNotesTimeline, labelNotes, llSkillsMatchSection, tvSkillsMatching, tvSkillsNotMatching));
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

        // JD fields binding
        EditText etJdLink = dialogView.findViewById(R.id.et_jd_link);
        Button btnUploadJd = dialogView.findViewById(R.id.btn_upload_jd_screenshot);
        FrameLayout flJdPreviewContainer = dialogView.findViewById(R.id.fl_jd_screenshot_container);
        ImageView ivJdPreview = dialogView.findViewById(R.id.iv_jd_screenshot_preview);
        View btnRemoveJd = dialogView.findViewById(R.id.btn_remove_jd_screenshot);

        activeFlJdPreviewContainer = flJdPreviewContainer;
        activeIvJdPreview = ivJdPreview;

        if (editCall != null) {
            etJdLink.setText(editCall.getJdLink());
            currentJdImagePath = editCall.getJdImagePath();
            if (currentJdImagePath != null && !currentJdImagePath.isEmpty()) {
                ivJdPreview.setImageURI(android.net.Uri.fromFile(new java.io.File(currentJdImagePath)));
                flJdPreviewContainer.setVisibility(View.VISIBLE);
            } else {
                flJdPreviewContainer.setVisibility(View.GONE);
            }
        } else {
            currentJdImagePath = null;
            flJdPreviewContainer.setVisibility(View.GONE);
        }

        btnUploadJd.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_CODE_PICK_JD_SCREENSHOT);
        });

        btnRemoveJd.setOnClickListener(v -> {
            currentJdImagePath = "";
            flJdPreviewContainer.setVisibility(View.GONE);
        });

        ivJdPreview.setOnClickListener(v -> {
            if (currentJdImagePath != null && !currentJdImagePath.isEmpty()) {
                showFullScreenImage(currentJdImagePath);
            }
        });

        // Bind Spinner choices
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.round_statuses, android.R.layout.simple_spinner_item);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRound.setAdapter(spinnerAdapter);

        final AlertDialog alertDialog = builder.create();
        alertDialog.setOnDismissListener(dialog -> {
            activeFlJdPreviewContainer = null;
            activeIvJdPreview = null;
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
            activeEtInterestRating = null;
            activeSpinnerRound = null;
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
            etInterestRating.setText(editCall.getInterestRating());

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
                    etInterestRating.setText(existingCall.getInterestRating());
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

        // Preview the in-call banner using whatever is currently in the dialog's fields
        // (not just the last-saved DB state), so edits can be checked before saving.
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
                refreshDashboardList();
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
            String interestRatingVal = etInterestRating != null ? etInterestRating.getText().toString().trim() : "";
            String noteSource = activeDialogManualUploadUsed
                    ? DatabaseHelper.NOTE_SOURCE_MANUAL : DatabaseHelper.NOTE_SOURCE_CALL;

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
                editCall.setJdLink(etJdLink.getText().toString().trim());
                editCall.setJdImagePath(currentJdImagePath != null ? currentJdImagePath : "");
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

                // Stay on this dialog and show the updated result instead of closing -
                // clear the note field (it's now saved) and refresh the call-history
                // timeline and the list behind the dialog.
                activeDialogManualUploadUsed = false;
                activeDialogManualUploadAIFailed = false;
                if (etNotes != null) etNotes.setText("");
                populateTimeline(llNotesTimeline, labelNotes, editCall.getId());
                refreshDashboardList();

                // A hand-typed note (not one already AI-processed via a recording
                // upload) also gets run through the AI so the round/next-call date
                // update automatically from what was written, same as a real call -
                // and the raw typed text itself gets rewritten into a clean AI summary.
                if (!noteToAdd.isEmpty() && (!noteWasFromManualUpload || noteFailedAI)) {
                    runAiOnManualNote(editCall.getId(), insertedNoteId, noteToAdd, spinnerRound, etTentativeSchedule,
                            llNotesTimeline, labelNotes, llSkillsMatchSection, tvSkillsMatching, tvSkillsNotMatching);
                }
                return;
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
                        dbHelper.insertNote(existingCall.getId(), noteToAdd, System.currentTimeMillis(), noteSource);
                    }
                    
                    // Update existing company fields with edits
                    existingCall.setCandidateName(candidate);
                    existingCall.setAppliedRole(role);
                    existingCall.setTentativeSchedule(schedule);
                    existingCall.setRoundStatus(round);
                    if (!recruiter.isEmpty()) {
                        existingCall.setRecruiterName(recruiter);
                    }
                    existingCall.setJdLink(etJdLink.getText().toString().trim());
                    existingCall.setJdImagePath(currentJdImagePath != null ? currentJdImagePath : "");
                    existingCall.setInterestRating(interestRatingVal);
                    dbHelper.updateJobCall(existingCall);
                    
                    Toast.makeText(requireContext(), "Linked to existing company " + existingCall.getCompanyName(), Toast.LENGTH_LONG).show();
                } else {
                    // Create new entry
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
                    newCall.setJdLink(etJdLink.getText().toString().trim());
                    newCall.setJdImagePath(currentJdImagePath != null ? currentJdImagePath : "");
                    newCall.setInterestRating(interestRatingVal);

                    long newId = dbHelper.insertJobCall(newCall);
                    if (newId != -1 && !noteToAdd.isEmpty()) {
                        dbHelper.insertNote(newId, noteToAdd, System.currentTimeMillis(), noteSource);
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

    /**
     * Fills the edit dialog's timeline with the same numbered "1st Call / 2nd Call…"
     * layout used in the caller-ID banner: oldest call first, each with its bulleted
     * notes and a delete option.
     */
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

        List<CallNote> notes = dbHelper.getNotesForJob(jobId);
        if (notes.isEmpty()) {
            if (label != null) label.setVisibility(View.GONE);
            return;
        }
        if (label != null) label.setVisibility(View.VISIBLE);

        // Oldest first so numbering reads 1st Call, 2nd Call, ... like the banner.
        List<CallNote> chronological = new ArrayList<>(notes);
        Collections.reverse(chronological);

        LayoutInflater inflater = getLayoutInflater();
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
        // One running counter across the whole chronological sequence; only the
        // word changes per entry: "Call" for a real call, "MCall" for a manual upload.
        int ordinal = 1;
        for (CallNote n : chronological) {
            String clean = cleanNoteText(n.note);
            if (clean.trim().isEmpty()) {
                // The filler-phrase filter (meant to declutter AI call summaries) ate
                // the entire note - fall back to the original text instead of silently
                // dropping a note the user can see was saved (e.g. a short hand-typed
                // note like "very interested in this role").
                clean = n.note == null ? "" : n.note;
            }
            if (clean.trim().isEmpty()) {
                continue;
            }

            String label2 = ordinal + getOrdinalSuffix(ordinal) + (n.isManual() ? " MCall" : " Call");

            View row = inflater.inflate(R.layout.item_call_note_row, container, false);
            ((TextView) row.findViewById(R.id.tv_call_ordinal)).setText(label2);
            ((TextView) row.findViewById(R.id.tv_call_date)).setText(sdf.format(new Date(n.timestamp)));
            ((TextView) row.findViewById(R.id.tv_call_points)).setText(bulletize(clean));

            long noteId = n.id;
            View delete = row.findViewById(R.id.btn_delete_call_note);
            delete.setOnClickListener(v -> {
                dbHelper.deleteNote(noteId, jobId);
                populateTimeline(container, label, jobId);
            });

            container.addView(row);
            ordinal++;
        }

        if (container.getChildCount() == 0 && label != null) {
            label.setVisibility(View.GONE);
        }
    }

    /** Filters out generic candidate-interest chatter lines, same rule as the call banner. */
    private String cleanNoteText(String rawText) {
        if (rawText == null) return "";
        String[] lines = rawText.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim().toLowerCase(Locale.getDefault());
            if (trimmed.contains("interested in") || trimmed.contains("is interested")
                    || trimmed.contains("of course") || trimmed.contains("ofcourse")) {
                continue;
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString();
    }

    private String bulletize(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.startsWith("•") && !trimmed.startsWith("-")) {
                trimmed = "• " + trimmed;
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append(trimmed);
        }
        return sb.toString();
    }

    private String getOrdinalSuffix(int number) {
        if (number >= 11 && number <= 13) return "th";
        switch (number % 10) {
            case 1: return "st";
            case 2: return "nd";
            case 3: return "rd";
            default: return "th";
        }
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
                    // Swipe Right: Delete / Dismiss
                    if (jobCall.getId() <= 0) {
                        String key = jobCall.getPhoneNumber() + "_" + jobCall.getTimestamp();
                        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);
                        java.util.Set<String> dismissed = new java.util.HashSet<>(prefs.getStringSet("dismissed_unlogged_calls", new java.util.HashSet<>()));
                        dismissed.add(key);
                        prefs.edit().putStringSet("dismissed_unlogged_calls", dismissed).apply();
                        Toast.makeText(requireContext(), "Call ignored", Toast.LENGTH_SHORT).show();
                        refreshDashboardList();
                    } else {
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
        } else if (requestCode == REQ_CODE_PICK_JD_SCREENSHOT && resultCode == android.app.Activity.RESULT_OK && data != null) {
            android.net.Uri selectedUri = data.getData();
            if (selectedUri != null) {
                handleJdScreenshotSelected(selectedUri);
            }
        }
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

                            // This dialog session now has a manual-upload transcription in
                            // the Notes field, so whatever gets saved is tagged "manual".
                            activeDialogManualUploadUsed = true;

                            // Check OpenAI API Key
                            String openAiKey = requireContext().getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE).getString("openai_api_key", "").trim();
                            if (openAiKey.isEmpty()) {
                                // Fallback: No OpenAI key -> Save transcription raw
                                activeDialogManualUploadAIFailed = true;
                                if (activeLlTranscriptionProgress != null) {
                                    activeLlTranscriptionProgress.setVisibility(View.GONE);
                                }
                                String currentNotes = etNotesField.getText().toString().trim();
                                etNotesField.setText(currentNotes.isEmpty() ? text : currentNotes + "\n" + text);
                                Toast.makeText(requireContext(), "Transcription success! Saved raw.", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            // Query OpenAI
                            if (activeTvTranscriptionStatus != null) {
                                activeTvTranscriptionStatus.setText("✨ Extracting fields using OpenAI...");
                            }
                            Toast.makeText(requireContext(), "✨ Running AI analysis...", Toast.LENGTH_SHORT).show();
                            OpenAiClient.extractFields(requireContext(), text, new OpenAiClient.OpenAiCallback() {
                                @Override
                                public void onSuccess(JSONObject result) {
                                    if (!isAdded()) return;
                                    try {
                                         String candidate = optClean(result, "candidate_name", "");
                                         if (!candidate.isEmpty() && activeEtCandidateName != null) {
                                             String current = activeEtCandidateName.getText().toString().trim();
                                             if (current.isEmpty()) {
                                                 activeEtCandidateName.setText(candidate);
                                             }
                                         }
                                         String company = optClean(result, "company_name", "");
                                         if (!company.isEmpty() && activeEtCompany != null) {
                                             String current = activeEtCompany.getText().toString().trim();
                                             if (current.isEmpty()) {
                                                 activeEtCompany.setText(company);
                                             }
                                         }
                                         String role = optClean(result, "applied_role", "");
                                         if (!role.isEmpty() && activeEtAppliedRole != null) {
                                             String current = activeEtAppliedRole.getText().toString().trim();
                                             if (current.isEmpty()) {
                                                 activeEtAppliedRole.setText(role);
                                             }
                                         }
                                         String round = optClean(result, "present_round", "");
                                         if (!round.isEmpty() && activeSpinnerRound != null) {
                                             setSpinnerSelection(activeSpinnerRound, round);
                                         }
                                         String schedule = optClean(result, "tentative_schedule", "");
                                         if (!schedule.isEmpty() && activeEtTentativeSchedule != null) {
                                             activeEtTentativeSchedule.setText(schedule);
                                         }
                                         String notice = optClean(result, "notice_period", "");
                                         if (!notice.isEmpty() && activeEtNoticePeriod != null) {
                                             activeEtNoticePeriod.setText(notice);
                                         }
                                         String agenda = optClean(result, "main_agenda", "");
                                         if (!agenda.isEmpty() && activeEtMainAgenda != null) {
                                             activeEtMainAgenda.setText(agenda);
                                         }
                                         String nextStepsVal = optClean(result, "next_steps", "");
                                         if (!nextStepsVal.isEmpty() && activeEtNextSteps != null) {
                                             activeEtNextSteps.setText(nextStepsVal);
                                         }
                                        
                                        String notesFromPoints = "";
                                        if (result.has("key_discussion_points")) {
                                            JSONArray arr = result.getJSONArray("key_discussion_points");
                                            StringBuilder sb = new StringBuilder();
                                            for (int i = 0; i < arr.length(); i++) {
                                                sb.append("• ").append(arr.getString(i)).append("\n");
                                            }
                                            notesFromPoints = sb.toString().trim();
                                            if (activeEtNotes != null) {
                                                activeEtNotes.setText(notesFromPoints);
                                            }
                                        }

                                        // Auto-save straight to the DB, same as a real call - the user
                                        // shouldn't have to also remember to tap Update after uploading a
                                        // recording. Only possible for an existing lead (editCall != null);
                                        // a brand-new lead still needs company/phone via Save.
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

                                                // Clear the notes field so a later tap on Update doesn't
                                                // insert this same text again as a second note.
                                                if (activeEtNotes != null) activeEtNotes.setText("");
                                                if (activeEtTentativeSchedule != null && !schedule.isEmpty()) {
                                                    activeEtTentativeSchedule.setText(current.getTentativeSchedule());
                                                }
                                                if (activeSpinnerRound != null) {
                                                    setSpinnerSelection(activeSpinnerRound, current.getRoundStatus());
                                                }
                                                populateTimeline(timelineContainer, timelineLabel, autoSaveJobId);
                                                populateSkillsMatch(skillsSection, tvSkillsMatchingRef, tvSkillsNotMatchingRef, current);
                                                refreshDashboardList();
                                                Toast.makeText(requireContext(), "Call logged automatically!", Toast.LENGTH_SHORT).show();
                                            }
                                        } else {
                                            Toast.makeText(requireContext(), "AI fields updated successfully!", Toast.LENGTH_SHORT).show();
                                        }
                                    } catch (Exception e) {
                                        DebugLogger.log(requireContext(), "Failed to parse OpenAI fields in dialog: " + e.getMessage());
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
                                    if (!isAdded()) return;
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
                            if (isAdded()) {
                                Toast.makeText(requireContext(), "Error transcribing: " + error, Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Runs a hand-typed note through the AI (same field extraction used for real
     * calls) so a manually noted "L1 scheduled tomorrow 2pm" or "not interested"
     * updates the round status and next-call date automatically. Silently does
     * nothing if no OpenAI key is configured. The DB is always updated on success;
     * the on-screen spinner/date field only if the dialog is still open.
     */
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

                    // Rewrite the raw hand-typed note in place into a clean AI summary,
                    // same as a real call gets bulleted key_discussion_points - instead
                    // of leaving the rough typed text sitting there untouched forever.
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
                        refreshDashboardList();
                        if (noteRewritten) {
                            Toast.makeText(requireContext(), "Note rewritten with AI.", Toast.LENGTH_SHORT).show();
                        }
                    }
                } catch (Exception e) {
                    DebugLogger.log(requireContext(), "runAiOnManualNote failed: " + e.getMessage());
                    if (isAdded() && getContext() != null) {
                        Toast.makeText(requireContext(), "AI rewrite failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            }

            @Override
            public void onError(String error) {
                DebugLogger.log(requireContext(), "runAiOnManualNote AI error: " + error);
                if (isAdded() && getContext() != null) {
                    Toast.makeText(requireContext(), "AI rewrite failed: " + error, Toast.LENGTH_LONG).show();
                }
            }
        });
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

    private String optClean(org.json.JSONObject json, String key, String fallback) {
        if (json == null || json.isNull(key)) return fallback;
        String val = json.optString(key, fallback).trim();
        if (val.equalsIgnoreCase("null")) return fallback;
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
        if (getContext() == null || path == null || path.isEmpty()) return;
        android.app.Dialog dialog = new android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_full_screen_image);
        ImageView iv = dialog.findViewById(R.id.iv_full_screen);
        iv.setImageURI(android.net.Uri.fromFile(new java.io.File(path)));
        iv.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void handleJdScreenshotSelected(android.net.Uri uri) {
        String path = copyUriToInternalStorage(uri);
        if (path != null) {
            currentJdImagePath = path;
            if (activeFlJdPreviewContainer != null && activeIvJdPreview != null) {
                activeIvJdPreview.setImageURI(android.net.Uri.fromFile(new java.io.File(path)));
                activeFlJdPreviewContainer.setVisibility(View.VISIBLE);
            }
        } else {
            Toast.makeText(requireContext(), "Failed to process selected image.", Toast.LENGTH_SHORT).show();
        }
    }
}

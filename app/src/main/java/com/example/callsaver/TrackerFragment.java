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
    private String[] screenshotPaths = new String[3];
    private final int[] containerIds = {R.id.fl_jd_screenshot_container_1, R.id.fl_jd_screenshot_container_2, R.id.fl_jd_screenshot_container_3};
    private final int[] previewIds = {R.id.iv_jd_screenshot_preview_1, R.id.iv_jd_screenshot_preview_2, R.id.iv_jd_screenshot_preview_3};
    private final int[] removeIds = {R.id.btn_remove_jd_screenshot_1, R.id.btn_remove_jd_screenshot_2, R.id.btn_remove_jd_screenshot_3};
    private View activeDialogView;

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
    private final String[] statuses = {"All", "Unlogged", "First time", "Screening", "Interested", "1st Round", "2nd Round", "Final Round", "HR / Salary", "Offered", "Not Interested", "Negative"};

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

        tvStatLeads = null;
        tvStatScreenings = null;
        tvStatInterviews = null;
        tvStatOffers = null;

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

        tvCheckDuplicates = null;


        // Setup filter chips
        setupFilterChips(view);

        // Bind Clear All Unlogged button
        View btnClearAllUnlogged = view.findViewById(R.id.btn_clear_all_unlogged);
        if (btnClearAllUnlogged != null) {
            btnClearAllUnlogged.setOnClickListener(v -> {
                new AlertDialog.Builder(requireContext())
                        .setTitle("Dismiss All Unlogged Calls")
                        .setMessage("Are you sure you want to dismiss all current unlogged call logs? This cannot be undone.")
                        .setPositiveButton("Dismiss All", (dialog, which) -> {
                            android.content.SharedPreferences p = requireContext().getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);
                            java.util.Set<String> dismissed = new java.util.HashSet<>(p.getStringSet("dismissed_unlogged_calls", new java.util.HashSet<>()));
                            
                            List<JobCall> unloggedList = getUnloggedCallLogs();
                            for (JobCall j : unloggedList) {
                                String key = j.getPhoneNumber() + "_" + j.getTimestamp();
                                dismissed.add(key);
                            }
                            
                            p.edit().putStringSet("dismissed_unlogged_calls", dismissed).apply();
                            Toast.makeText(requireContext(), "Dismissed " + unloggedList.size() + " unlogged calls", Toast.LENGTH_SHORT).show();
                            
                            refreshDashboardList();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

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
                R.id.chip_all, R.id.chip_unlogged, R.id.chip_first_time, R.id.chip_screening, R.id.chip_interested, R.id.chip_1st_round,
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
            
            boolean matchesStatus;
            if ("Unlogged".equals(status)) {
                matchesStatus = (call.getId() <= 0);
            } else {
                if (call.getId() <= 0) {
                    matchesStatus = false;
                } else {
                    matchesStatus = "All".equals(status) ||
                            (call.getRoundStatus() != null && call.getRoundStatus().equals(status));
                }
            }

            if (matchesQuery && matchesStatus) {
                filteredList.add(call);
            }
        }

        callList.clear();
        callList.addAll(filteredList);
        adapter.notifyDataSetChanged();

        View btnClear = getView() != null ? getView().findViewById(R.id.btn_clear_all_unlogged) : null;
        if (btnClear != null) {
            if ("Unlogged".equals(status) && !filteredList.isEmpty()) {
                btnClear.setVisibility(View.VISIBLE);
            } else {
                btnClear.setVisibility(View.GONE);
            }
        }

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

        List<Long> loggedTimestamps = dbHelper.getAllLoggedTimestamps();
        List<DatabaseHelper.PhoneJobMapping> mappings = dbHelper.getAllPhoneJobMappings();

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

                int scanCount = 0;
                int addedCount = 0;
                do {
                    String number = cursor.getString(numberIdx);
                    int type = cursor.getInt(typeIdx);
                    long date = cursor.getLong(dateIdx);
                    int duration = cursor.getInt(durationIdx);

                    scanCount++;

                    // Skip missed and rejected calls
                    if (type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE) {
                        continue;
                    }
                    // For incoming and outgoing, only show if duration > 10 seconds
                    if (type == CallLog.Calls.INCOMING_TYPE || type == CallLog.Calls.OUTGOING_TYPE) {
                        if (duration <= 10) {
                            continue;
                        }
                    } else {
                        // Skip other types (e.g. voicemail, blocked, etc.)
                        continue;
                    }

                    long endTime = date + duration * 1000L;
                    // Check if this call event (by its end time) is already logged in history in memory
                    boolean isLogged = false;
                    for (long loggedTime : loggedTimestamps) {
                        if (Math.abs(loggedTime - endTime) < 5000) {
                            isLogged = true;
                            break;
                        }
                    }

                    if (!isLogged) {
                        // Check if dismissed
                        String key = number + "_" + endTime;
                        if (!dismissed.contains(key)) {
                            String badgeType = "Incoming";
                            String notesDesc = "Call";
                            if (type == CallLog.Calls.INCOMING_TYPE) {
                                badgeType = "Incoming";
                                notesDesc = "Incoming Call";
                            } else if (type == CallLog.Calls.OUTGOING_TYPE) {
                                badgeType = "Outgoing";
                                notesDesc = "Outgoing Call";
                            }

                            String displayName = getContactNameByNumber(requireContext(), number);
                            if (displayName == null || displayName.trim().isEmpty()) {
                                for (DatabaseHelper.PhoneJobMapping mapping : mappings) {
                                    if (android.telephony.PhoneNumberUtils.compare(requireContext(), mapping.phoneNumber, number)) {
                                        String recName = mapping.recruiterName;
                                        String compName = mapping.companyName;
                                        String result = null;
                                        if (recName != null && !recName.trim().isEmpty()) {
                                            result = recName.trim();
                                        }
                                        if (compName != null && !compName.trim().isEmpty()) {
                                            if (result != null && !result.equalsIgnoreCase(compName)) {
                                                result = result + " @ " + compName.trim();
                                            } else {
                                                result = compName.trim();
                                            }
                                        }
                                        displayName = result;
                                        break;
                                    }
                                }
                            }
                            if (displayName == null || displayName.trim().isEmpty()) {
                                displayName = "Unlogged Call";
                            }

                            JobCall unloggedCall = new JobCall(number, displayName, badgeType, "", notesDesc, duration, endTime);
                            unloggedCall.setId((int) (-1 * (Math.abs(key.hashCode()) % 1000000 + 1)));
                            unlogged.add(unloggedCall);
                            addedCount++;
                        }
                    }
                } while (cursor.moveToNext() && scanCount < 200 && addedCount < 40);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return unlogged;
    }

    private void updateStatsAndFilter() {
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
            if (st.equals("First time") || st.equals("Screening") || st.equals("Interested") || st.equals("HR / Salary")) {
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
     * Refreshes the calls displayed on the RecyclerView from the SQLite DB.
     */
    public void refreshDashboardList() {
        if (dbHelper == null) return;
        List<JobCall> updatedCalls = dbHelper.getAllJobCallsSortedByRecentActivity();
        
        allCallsList.clear();
        allCallsList.addAll(updatedCalls);
        updateStatsAndFilter();

        new Thread(() -> {
            List<JobCall> unloggedCalls = getUnloggedCallLogs();
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    allCallsList.clear();
                    allCallsList.addAll(updatedCalls);
                    allCallsList.addAll(unloggedCalls);
                    java.util.Collections.sort(allCallsList, (a, b) -> Long.compare(b.getLastActivityTime(), a.getLastActivityTime()));
                    updateStatsAndFilter();
                });
            }
        }).start();
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
        EditText etNoticePeriod = null; // Notice Period removed from UI
        EditText etMainAgenda = null;
        EditText etNextSteps = null;

        Spinner spinnerInterestStatus = dialogView.findViewById(R.id.spinner_interest_status);

        activeEtCandidateName = etCandidateName;
        activeEtCompany = etCompany;
        activeEtAppliedRole = etAppliedRole;
        activeEtTentativeSchedule = etTentativeSchedule;
        activeEtNoticePeriod = etNoticePeriod;
        activeEtMainAgenda = etMainAgenda;
        activeEtNotes = etNotes;
        activeEtNextSteps = etNextSteps;
        activeSpinnerRound = spinnerRound;
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

        activeDialogNotesField = etNotes;
        activeDialogTilNotes = tilNotes;



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
                refreshDashboardList();

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

            refreshDashboardList();
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
        } else if (requestCode >= REQ_CODE_PICK_JD_SCREENSHOT && requestCode < REQ_CODE_PICK_JD_SCREENSHOT + 3 
                && resultCode == android.app.Activity.RESULT_OK && data != null) {
            android.net.Uri selectedUri = data.getData();
            if (selectedUri != null) {
                int index = requestCode - REQ_CODE_PICK_JD_SCREENSHOT;
                handleJdScreenshotSelectedAtIndex(index, selectedUri);
            }
        }
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

                    String company = optClean(result, "company_name", "");
                    if (!company.isEmpty() && (current.getCompanyName() == null || current.getCompanyName().isEmpty() || current.getCompanyName().equalsIgnoreCase("Unknown Recruiter") || current.getCompanyName().equalsIgnoreCase("Unknown Company"))) {
                        current.setCompanyName(company);
                        dbHelper.updateJobCall(current);
                    }

                    String role = optClean(result, "applied_role", "");
                    if (!role.isEmpty() && (current.getAppliedRole() == null || current.getAppliedRole().isEmpty())) {
                        current.setAppliedRole(role);
                        dbHelper.updateJobCall(current);
                    }

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
}

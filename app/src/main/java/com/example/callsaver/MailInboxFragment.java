package com.example.callsaver;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MailInboxFragment extends Fragment implements MailInboxAdapter.OnMailClickListener {

    private RecyclerView rvMailInbox;
    private MailInboxAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private EditText etSearch;
    private TextView tvAccountEmail;

    private DatabaseHelper dbHelper;
    private final List<EmailMessage> allFetchedEmails = new ArrayList<>();

    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable searchRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_mail_inbox, container, false);

        if (getContext() != null) {
            dbHelper = new DatabaseHelper(getContext());
        }

        View toolbar = view.findViewById(R.id.toolbar_mail_inbox);
        if (toolbar != null) toolbar.setVisibility(View.GONE);

        rvMailInbox = view.findViewById(R.id.rv_mail_inbox);
        swipeRefresh = view.findViewById(R.id.swipe_refresh_inbox);
        progressBar = view.findViewById(R.id.progress_inbox);
        tvEmpty = view.findViewById(R.id.tv_empty_inbox);
        etSearch = view.findViewById(R.id.et_search_inbox);
        tvAccountEmail = view.findViewById(R.id.tv_inbox_account_email);
        ImageView btnRefresh = view.findViewById(R.id.btn_refresh_inbox);

        if (getContext() != null) {
            String accountEmail = GmailService.getAccountEmail(requireContext());
            if (tvAccountEmail != null) tvAccountEmail.setText(accountEmail);
        }

        if (getContext() != null) {
            rvMailInbox.setLayoutManager(new LinearLayoutManager(requireContext()));
            adapter = new MailInboxAdapter(requireContext(), this);
            rvMailInbox.setAdapter(adapter);
        }

        swipeRefresh.setOnRefreshListener(this::loadInboxEmails);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> loadInboxEmails());
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    // Instant filter over whatever's already cached/fetched...
                    filterEmails(s.toString());
                    // ...then, after a short pause, actually search Gmail's server for
                    // this term - otherwise search only ever finds mail within the last
                    // 30 fetched messages, missing anything older.
                    searchHandler.removeCallbacks(searchRunnable);
                    String query = s.toString();
                    if (!query.trim().isEmpty()) {
                        searchRunnable = () -> searchGmailServerSide(query);
                        searchHandler.postDelayed(searchRunnable, 500);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        com.google.android.material.chip.Chip chipAll = view.findViewById(R.id.chip_filter_all);
        com.google.android.material.chip.Chip chipUnassigned = view.findViewById(R.id.chip_filter_unassigned);
        com.google.android.material.chip.Chip chipInterviews = view.findViewById(R.id.chip_filter_interviews);

        if (chipAll != null && chipUnassigned != null && chipInterviews != null) {
            chipAll.setOnClickListener(v -> {
                selectedFilterMode = 0;
                chipAll.setChecked(true);
                chipUnassigned.setChecked(false);
                chipInterviews.setChecked(false);
                filterEmails(etSearch != null ? etSearch.getText().toString() : "");
            });
            chipUnassigned.setOnClickListener(v -> {
                selectedFilterMode = 1;
                chipAll.setChecked(false);
                chipUnassigned.setChecked(true);
                chipInterviews.setChecked(false);
                filterEmails(etSearch != null ? etSearch.getText().toString() : "");
            });
            chipInterviews.setOnClickListener(v -> {
                selectedFilterMode = 2;
                chipAll.setChecked(false);
                chipUnassigned.setChecked(false);
                chipInterviews.setChecked(true);
                filterEmails(etSearch != null ? etSearch.getText().toString() : "");
            });
        }

        loadInboxEmails();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadInboxEmails();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
    }

    private void loadInboxEmails() {
        if (dbHelper != null) {
            List<EmailMessage> cached = dbHelper.getAllCachedEmails();
            if (cached != null && !cached.isEmpty()) {
                allFetchedEmails.clear();
                allFetchedEmails.addAll(cached);
                filterEmails(etSearch != null ? etSearch.getText().toString() : "");
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            }
        }

        if (allFetchedEmails.isEmpty() && progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);

        if (getContext() == null) return;
        GmailService.fetchInboxMessagesAsync(requireContext(), 30, new GmailService.FetchCallback<List<EmailMessage>>() {
            @Override
            public void onSuccess(List<EmailMessage> result) {
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);

                    if (result != null && !result.isEmpty()) {
                        allFetchedEmails.clear();
                        allFetchedEmails.addAll(result);
                    }

                    filterEmails(etSearch != null ? etSearch.getText().toString() : "");
                });
            }

            @Override
            public void onError(String error) {
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);

                    if (allFetchedEmails.isEmpty()) {
                        if (tvEmpty != null) {
                            tvEmpty.setText("⚠️ Failed to sync with Gmail.\n" + error);
                            tvEmpty.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    private int selectedFilterMode = 0; // 0 = All, 1 = Unassigned, 2 = Interview Invites

    /**
     * Runs the typed query against Gmail's own server-side search instead of only the
     * last ~30 cached messages, so searching finds old mail too. Results are merged
     * into the working list (existing matches from the local cache are kept, server
     * results are added on top, deduped by message id) and re-filtered/displayed.
     */
    private void searchGmailServerSide(String query) {
        if (getContext() == null || query == null || query.trim().isEmpty()) return;
        GmailService.fetchInboxMessagesAsync(requireContext(), query, 50, new GmailService.FetchCallback<List<EmailMessage>>() {
            @Override
            public void onSuccess(List<EmailMessage> result) {
                if (getActivity() == null || result == null) return;
                requireActivity().runOnUiThread(() -> {
                    // gmailMessageId (not the local DB row id, which is 0/unset for
                    // messages fetched fresh from the server and not yet cached) is the
                    // actual unique identifier to dedup on.
                    java.util.Set<String> existingIds = new java.util.HashSet<>();
                    for (EmailMessage m : allFetchedEmails) existingIds.add(m.getGmailMessageId());
                    for (EmailMessage m : result) {
                        if (!existingIds.contains(m.getGmailMessageId())) {
                            allFetchedEmails.add(m);
                            existingIds.add(m.getGmailMessageId());
                        }
                    }
                    // Still showing the same search box text? Re-apply the filter now
                    // that older matching mail has been merged in.
                    if (etSearch != null && query.equals(etSearch.getText().toString())) {
                        filterEmails(query);
                    }
                });
            }

            @Override
            public void onError(String error) {
                // Silent - the local filter already showed whatever was cached; no need
                // to interrupt the user with an error for a background search refinement.
            }
        });
    }

    private void filterEmails(String query) {
        String q = query != null ? query.trim().toLowerCase() : "";
        List<EmailMessage> filtered = new ArrayList<>();

        for (EmailMessage mail : allFetchedEmails) {
            boolean matchesSearch = q.isEmpty()
                    || mail.getSubject().toLowerCase().contains(q)
                    || mail.getSender().toLowerCase().contains(q)
                    || mail.getRecipient().toLowerCase().contains(q)
                    || mail.getSnippet().toLowerCase().contains(q)
                    || mail.getBody().toLowerCase().contains(q);

            boolean matchesChip = true;
            if (selectedFilterMode == 1) {
                matchesChip = mail.getJobCallId() <= 0;
            } else if (selectedFilterMode == 2) {
                String combined = (mail.getSubject() + " " + mail.getBody()).toLowerCase();
                matchesChip = combined.contains("interview") || combined.contains("invite")
                        || combined.contains("schedule") || combined.contains("slot")
                        || combined.contains("assessment") || combined.contains("round");
            }

            if (matchesSearch && matchesChip) {
                filtered.add(mail);
            }
        }

        if (adapter != null) adapter.setEmails(filtered);
        if (tvEmpty != null) {
            if (filtered.isEmpty()) {
                tvEmpty.setText("No emails found matching \"" + query + "\"");
                tvEmpty.setVisibility(View.VISIBLE);
            } else {
                tvEmpty.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public void onMailClick(EmailMessage email) {
        showMailDetailDialog(email);
    }

    @Override
    public void onAssignToLogClick(EmailMessage email) {
        showAssignMailDialog(email);
    }

    public void showMailDetailDialog(EmailMessage email) {
        if (getContext() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_mail_detail, null);
        builder.setView(view);

        Dialog dialog = builder.create();

        TextView tvSubject = view.findViewById(R.id.tv_detail_subject);
        TextView tvFrom = view.findViewById(R.id.tv_detail_from);
        TextView tvTo = view.findViewById(R.id.tv_detail_to);
        TextView tvDate = view.findViewById(R.id.tv_detail_date);
        TextView tvBody = view.findViewById(R.id.tv_detail_body);
        WebView webView = view.findViewById(R.id.webview_detail_body);
        ImageView btnClose = view.findViewById(R.id.btn_close_detail);
        View btnAssign = view.findViewById(R.id.btn_detail_assign);
        View btnUnassign = view.findViewById(R.id.btn_detail_unassign);

        if (tvSubject != null) tvSubject.setText(email.getSubject());
        if (tvFrom != null) tvFrom.setText("From: " + email.getSender());
        if (tvTo != null) tvTo.setText("To: " + (email.getRecipient().isEmpty() ? GmailService.getAccountEmail(requireContext()) : email.getRecipient()));

        if (tvDate != null) {
            long ts = email.getReceivedTimestamp();
            if (ts > 0) {
                tvDate.setText("Date: " + DateFormat.format("E, dd MMM yyyy, h:mm a", new Date(ts)));
            } else {
                tvDate.setText("");
            }
        }

        String body = email.getBody();
        if (body == null || body.trim().isEmpty()) {
            body = email.getSnippet();
        }

        if (body != null && !body.trim().isEmpty()) {
            boolean isHtml = body.contains("<html") || body.contains("<div") || body.contains("<p>") || body.contains("<span") || body.contains("<br") || body.contains("<table");
            if (isHtml && webView != null) {
                webView.setVisibility(View.VISIBLE);
                if (tvBody != null) tvBody.setVisibility(View.GONE);

                WebSettings webSettings = webView.getSettings();
                webSettings.setJavaScriptEnabled(true);
                webSettings.setDomStorageEnabled(true);
                webSettings.setLoadWithOverviewMode(true);
                webSettings.setUseWideViewPort(true);

                String styledHtml = "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><style>" +
                        "body { color: #E0E0E0; background-color: #121212; font-family: sans-serif; font-size: 14px; padding: 12px; line-height: 1.6; word-wrap: break-word; }" +
                        "a { color: #818CF8; }" +
                        "img { max-width: 100% !important; height: auto !important; }" +
                        "table { max-width: 100% !important; width: 100% !important; }" +
                        "</style></head><body>" + body + "</body></html>";

                webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null);
            } else if (tvBody != null) {
                tvBody.setText(isHtml ? Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY) : body);
                tvBody.setVisibility(View.VISIBLE);
                if (webView != null) webView.setVisibility(View.GONE);
            }
        } else {
            if (tvBody != null) {
                tvBody.setText("(No email content available)");
                tvBody.setVisibility(View.VISIBLE);
            }
            if (webView != null) webView.setVisibility(View.GONE);
        }

        if (email.getJobCallId() > 0 && btnUnassign != null) {
            btnUnassign.setVisibility(View.VISIBLE);
            btnUnassign.setOnClickListener(v -> {
                if (dbHelper != null) dbHelper.unassignJobEmail(email.getId());
                Toast.makeText(requireContext(), "Email unassigned from call log!", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                loadInboxEmails();
            });
        }

        if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
        if (btnAssign != null) {
            btnAssign.setOnClickListener(v -> {
                dialog.dismiss();
                showAssignMailDialog(email);
            });
        }

        dialog.show();
    }

    private static class AssignOption {
        String label;
        JobCall existingCall;
        String unloggedPhone;
        String companyName;        // set on company-selection-step options
        List<JobCall> companyMembers; // set when this option represents a whole company (step 1)
    }

    private void showAssignMailDialog(EmailMessage email) {
        if (getContext() == null || dbHelper == null) return;

        List<JobCall> allCalls = dbHelper.getAllJobCalls();
        String domain = extractDomain(email.getSenderEmail());

        // Group existing logs by company first, so assigning is "pick the company, then
        // (if it has more than one lead) pick the specific person" instead of one long
        // flat list mixing every lead from every company together.
        java.util.Map<String, List<JobCall>> byCompany = new java.util.LinkedHashMap<>();
        for (JobCall call : allCalls) {
            String company = call.getCompanyName() != null && !call.getCompanyName().trim().isEmpty()
                    ? call.getCompanyName().trim() : "Call #" + call.getId();
            byCompany.computeIfAbsent(company, k -> new ArrayList<>()).add(call);
        }

        List<AssignOption> companyOptions = new ArrayList<>();
        for (java.util.Map.Entry<String, List<JobCall>> e : byCompany.entrySet()) {
            String company = e.getKey();
            List<JobCall> members = e.getValue();
            boolean matchesDomain = !domain.isEmpty() && company.toLowerCase().contains(domain);

            AssignOption opt = new AssignOption();
            opt.companyName = company;
            opt.companyMembers = members;
            opt.label = (matchesDomain ? "⭐ RECOMMENDED: " : "📌 ") + company
                    + (members.size() > 1 ? "  (" + members.size() + " leads)" : "");
            if (matchesDomain) {
                companyOptions.add(0, opt);
            } else {
                companyOptions.add(opt);
            }
        }

        // Unlogged recent calls from system phone history
        List<String> unloggedPhones = getUnloggedPhoneNumbers(requireContext());
        for (String phone : unloggedPhones) {
            AssignOption opt = new AssignOption();
            opt.unloggedPhone = phone;
            opt.label = "➕ Create Log for Recent Call: " + phone;
            companyOptions.add(opt);
        }

        AssignOption blankOpt = new AssignOption();
        blankOpt.label = "➕ Create New Blank Log";
        companyOptions.add(blankOpt);

        new AlertDialog.Builder(requireContext())
                .setTitle("📌 Assign Mail - Select Company\n" + email.getSubject())
                .setAdapter(buildAssignAdapter(companyOptions), (dialog, which) -> {
                    AssignOption selected = companyOptions.get(which);
                    if (selected.companyMembers != null) {
                        if (selected.companyMembers.size() == 1) {
                            attachEmailToExistingCall(selected.companyMembers.get(0), email);
                        } else {
                            showAssignLeadWithinCompanyDialog(selected.companyName, selected.companyMembers, email);
                        }
                    } else {
                        createNewLogAndAttach(selected.unloggedPhone, domain, email);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Second step, only shown when the chosen company has 2+ leads: pick which one by name/role/phone. */
    private void showAssignLeadWithinCompanyDialog(String companyName, List<JobCall> members, EmailMessage email) {
        List<AssignOption> leadOptions = new ArrayList<>();
        for (JobCall call : members) {
            AssignOption opt = new AssignOption();
            opt.existingCall = call;
            String recruiter = call.getRecruiterName();
            opt.label = "👤 " + (recruiter != null && !recruiter.trim().isEmpty() ? recruiter.trim() : "(no name)");
            if (call.getAppliedRole() != null && !call.getAppliedRole().isEmpty()) {
                opt.label += " - " + call.getAppliedRole();
            }
            if (call.getPhoneNumber() != null && !call.getPhoneNumber().isEmpty()) {
                opt.label += "  " + call.getPhoneNumber();
            }
            leadOptions.add(opt);
        }
        AssignOption newLeadOpt = new AssignOption();
        newLeadOpt.label = "➕ New lead under " + companyName;
        newLeadOpt.companyName = companyName;
        leadOptions.add(newLeadOpt);

        new AlertDialog.Builder(requireContext())
                .setTitle("📌 " + companyName + " - Select Person")
                .setAdapter(buildAssignAdapter(leadOptions), (dialog, which) -> {
                    AssignOption selected = leadOptions.get(which);
                    if (selected.existingCall != null) {
                        attachEmailToExistingCall(selected.existingCall, email);
                    } else {
                        JobCall newCall = new JobCall();
                        newCall.setCompanyName(selected.companyName);
                        newCall.setAppliedRole(email.getSubject());
                        newCall.setRoundStatus("Lead");
                        newCall.setTimestamp(System.currentTimeMillis());
                        checkAutoInterviewInvite(newCall, email);
                        long newJobId = dbHelper.insertJobCall(newCall);
                        dbHelper.insertJobEmail(newJobId, email.getGmailMessageId(), email.getSender(), email.getRecipient(), email.getSubject(), email.getSnippet(), email.getBody(), email.getReceivedTimestamp());
                        Toast.makeText(requireContext(), "Created new lead under " + selected.companyName + " & attached email!", Toast.LENGTH_SHORT).show();
                        triggerAiEmailExtraction(newJobId, newCall, email);
                    }
                })
                .setNegativeButton("Back", null)
                .show();
    }

    private void attachEmailToExistingCall(JobCall chosenCall, EmailMessage email) {
        checkAutoInterviewInvite(chosenCall, email);
        dbHelper.updateJobCall(chosenCall);
        dbHelper.insertJobEmail(chosenCall.getId(), email.getGmailMessageId(), email.getSender(), email.getRecipient(), email.getSubject(), email.getSnippet(), email.getBody(), email.getReceivedTimestamp());
        Toast.makeText(requireContext(), "Attached email to " + chosenCall.getCompanyName() + " log!", Toast.LENGTH_SHORT).show();
        triggerAiEmailExtraction(chosenCall.getId(), chosenCall, email);
    }

    private void createNewLogAndAttach(String unloggedPhone, String domain, EmailMessage email) {
        JobCall newCall = new JobCall();
        if (unloggedPhone != null) {
            newCall.setPhoneNumber(unloggedPhone);
        }
        String companyName = email.getSenderDisplayName();
        if (companyName.equalsIgnoreCase(email.getSenderEmail())) {
            if (!domain.isEmpty()) {
                companyName = capitalize(domain);
            }
        }
        newCall.setCompanyName(companyName);
        newCall.setAppliedRole(email.getSubject());
        newCall.setRoundStatus("Lead");
        newCall.setTimestamp(System.currentTimeMillis());

        checkAutoInterviewInvite(newCall, email);

        long newJobId = dbHelper.insertJobCall(newCall);
        dbHelper.insertJobEmail(newJobId, email.getGmailMessageId(), email.getSender(), email.getRecipient(), email.getSubject(), email.getSnippet(), email.getBody(), email.getReceivedTimestamp());

        Toast.makeText(requireContext(), "Created new log" + (unloggedPhone != null ? " for " + unloggedPhone : "") + " & attached email!", Toast.LENGTH_SHORT).show();
        triggerAiEmailExtraction(newJobId, newCall, email);
    }

    private ArrayAdapter<String> buildAssignAdapter(List<AssignOption> options) {
        List<String> labels = new ArrayList<>();
        for (AssignOption opt : options) labels.add(opt.label);
        return new ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1, labels) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                if (tv != null) {
                    tv.setTextColor(getResources().getColor(R.color.text_primary));
                    tv.setTextSize(14);
                    int pad = (int) (12 * getResources().getDisplayMetrics().density);
                    tv.setPadding(pad, pad, pad, pad);
                }
                return v;
            }
        };
    }

    private List<String> getUnloggedPhoneNumbers(Context context) {
        List<String> list = new ArrayList<>();
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return list;
        }
        try {
            android.database.Cursor cursor = context.getContentResolver().query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    new String[]{android.provider.CallLog.Calls.NUMBER},
                    null, null, android.provider.CallLog.Calls.DATE + " DESC"
            );
            if (cursor != null) {
                List<DatabaseHelper.PhoneJobMapping> mappings = dbHelper.getAllPhoneJobMappings();
                int count = 0;
                while (cursor.moveToNext() && count < 5) {
                    String number = cursor.getString(0);
                    if (number != null && !number.trim().isEmpty()) {
                        boolean alreadyLogged = false;
                        for (DatabaseHelper.PhoneJobMapping m : mappings) {
                            if (android.telephony.PhoneNumberUtils.compare(context, m.phoneNumber, number)) {
                                alreadyLogged = true;
                                break;
                            }
                        }
                        if (!alreadyLogged && !list.contains(number)) {
                            list.add(number);
                            count++;
                        }
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    private void checkAutoInterviewInvite(JobCall call, EmailMessage email) {
        if (call == null || email == null) return;
        String combined = (email.getSubject() + " " + email.getBody()).toLowerCase();
        boolean isInterviewInvite = combined.contains("interview") || combined.contains("invite")
                || combined.contains("schedule") || combined.contains("slot")
                || combined.contains("assessment") || combined.contains("round");

        if (isInterviewInvite) {
            if ("Lead".equalsIgnoreCase(call.getRoundStatus()) || "First time".equalsIgnoreCase(call.getRoundStatus()) || call.getRoundStatus().isEmpty()) {
                call.setRoundStatus("1st Round");
            }
            String currentTags = call.getTags() != null ? call.getTags() : "";
            if (!currentTags.toLowerCase().contains("interview")) {
                call.setTags(currentTags.isEmpty() ? "Interview Scheduled" : currentTags + ", Interview Scheduled");
            }
        }
    }

    private void triggerAiEmailExtraction(long targetJobId, JobCall jobCall, EmailMessage email) {
        if (getContext() == null) return;
        OpenAiClient.extractFieldsFromEmail(requireContext(), email.getSubject(), email.getBody(), new OpenAiClient.OpenAiCallback() {
            @Override
            public void onSuccess(org.json.JSONObject result) {
                // Wrapped in try/catch so a parsing hiccup can't silently drop the email
                // note/JD - the attach action itself already succeeded, we just want to
                // know if this AI follow-up step failed instead of it looking like
                // nothing happened at all.
                try {
                    processEmailExtractionResult(targetJobId, jobCall, result, email);
                } catch (Exception e) {
                    insertFallbackEmailNote(targetJobId, email);
                    if (getActivity() != null) {
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "Email attached - AI summary failed (" + e.getMessage() + "), added a basic note instead.", Toast.LENGTH_LONG).show());
                    }
                }
            }

            @Override
            public void onError(String error) {
                // AI step failed entirely (missing/invalid API key, network error, etc.)
                // - still guarantee an Email Note shows up, just built from the email
                // itself instead of AI-extracted keywords.
                insertFallbackEmailNote(targetJobId, email);
                if (getActivity() != null) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "Email attached - AI summary failed (" + error + "), added a basic note instead.", Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    /** Guarantees an Email Note exists even when the AI extraction fails or returns
     *  no usable keywords - built directly from the email's subject/snippet instead. */
    private void insertFallbackEmailNote(long targetJobId, EmailMessage email) {
        if (dbHelper == null || targetJobId <= 0) return;
        String subject = email.getSubject();
        String snippet = email.getSnippet();
        StringBuilder sb = new StringBuilder();
        if (subject != null && !subject.trim().isEmpty()) sb.append("• ").append(subject.trim());
        if (snippet != null && !snippet.trim().isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("• ").append(snippet.trim());
        }
        if (sb.length() == 0) sb.append("• Email attached (no summary available)");
        dbHelper.insertNote(targetJobId, sb.toString(), System.currentTimeMillis(), DatabaseHelper.NOTE_SOURCE_EMAIL);
    }

    private void processEmailExtractionResult(long targetJobId, JobCall jobCall, org.json.JSONObject result, EmailMessage email) {
                if (result == null || getContext() == null) return;
                String aiComp = result.optString("company_name", "").trim();
                String aiRole = result.optString("applied_role", "").trim();
                String aiRec = result.optString("recruiter_name", "").trim();
                String aiJd = result.optString("job_description", "").trim();
                org.json.JSONArray aiPoints = result.optJSONArray("key_discussion_points");

                boolean updated = false;
                String existingComp = jobCall.getCompanyName() != null ? jobCall.getCompanyName().trim() : "";
                if (!aiComp.isEmpty() && !"null".equalsIgnoreCase(aiComp)) {
                    if (existingComp.isEmpty() || existingComp.contains("@")) {
                        jobCall.setCompanyName(aiComp);
                        updated = true;
                    } else if (!existingComp.equalsIgnoreCase(aiComp) && !existingComp.toLowerCase().contains(aiComp.toLowerCase())) {
                        final String newComp = aiComp;
                        final String combinedComp = newComp + ", " + existingComp;
                        if (getActivity() != null) {
                            requireActivity().runOnUiThread(() -> {
                                new AlertDialog.Builder(requireContext())
                                        .setTitle("⚠️ Company Name Difference Detected")
                                        .setMessage("Note/Log says: \"" + existingComp + "\"\nEmail extracted: \"" + newComp + "\"\n\nWhich format would you like to use?")
                                        .setPositiveButton("Use \"" + newComp + "\"", (dialog, which) -> {
                                            jobCall.setCompanyName(newComp);
                                            dbHelper.updateJobCall(jobCall);
                                            Toast.makeText(requireContext(), "Updated company to " + newComp, Toast.LENGTH_SHORT).show();
                                        })
                                        .setNeutralButton("Combine: \"" + combinedComp + "\"", (dialog, which) -> {
                                            jobCall.setCompanyName(combinedComp);
                                            dbHelper.updateJobCall(jobCall);
                                            Toast.makeText(requireContext(), "Updated company to " + combinedComp, Toast.LENGTH_SHORT).show();
                                        })
                                        .setNegativeButton("Keep \"" + existingComp + "\"", null)
                                        .show();
                            });
                        }
                    }
                }
                if (!aiRole.isEmpty() && !"null".equalsIgnoreCase(aiRole) && (jobCall.getAppliedRole() == null || jobCall.getAppliedRole().isEmpty())) {
                    jobCall.setAppliedRole(aiRole);
                    updated = true;
                }
                if (!aiRec.isEmpty() && !"null".equalsIgnoreCase(aiRec) && (jobCall.getRecruiterName() == null || jobCall.getRecruiterName().isEmpty())) {
                    jobCall.setRecruiterName(aiRec);
                    updated = true;
                }
                if (!aiJd.isEmpty() && !"null".equalsIgnoreCase(aiJd)) {
                    jobCall.setJdText(aiJd);
                    updated = true;
                }

                if (updated) {
                    dbHelper.updateJobCall(jobCall);
                }

                StringBuilder sb = new StringBuilder();
                if (aiPoints != null && aiPoints.length() > 0) {
                    for (int i = 0; i < aiPoints.length(); i++) {
                        String pt = aiPoints.optString(i, "").trim();
                        if (!pt.isEmpty()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append("• ").append(pt);
                        }
                    }
                }
                if (sb.length() > 0) {
                    dbHelper.insertNote(targetJobId, sb.toString(), System.currentTimeMillis(), DatabaseHelper.NOTE_SOURCE_EMAIL);
                } else {
                    // The AI call succeeded but returned no usable keywords (short/blank
                    // email, model declined, etc.) - still guarantee an Email Note shows
                    // up instead of silently having nothing to show.
                    insertFallbackEmailNote(targetJobId, email);
                }
                if (getActivity() != null) {
                    requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "🤖 AI auto-filled details & summary notes from email!", Toast.LENGTH_SHORT).show()
                    );
                }
            }

    private static String extractDomain(String email) {
        if (email == null || !email.contains("@")) return "";
        String domain = email.substring(email.indexOf("@") + 1);
        if (domain.contains(".")) {
            domain = domain.substring(0, domain.indexOf("."));
        }
        if ("gmail".equalsIgnoreCase(domain) || "yahoo".equalsIgnoreCase(domain) || "outlook".equalsIgnoreCase(domain) || "hotmail".equalsIgnoreCase(domain)) {
            return "";
        }
        return domain;
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}

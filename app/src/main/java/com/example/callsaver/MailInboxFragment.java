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
                    filterEmails(s.toString());
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

    private void showAssignMailDialog(EmailMessage email) {
        if (getContext() == null || dbHelper == null) return;
        List<JobCall> allCalls = dbHelper.getAllJobCalls();

        List<String> options = new ArrayList<>();
        options.add("➕ Create New Call Log for this Email");

        String senderEmail = email.getSenderEmail();
        int suggestedIndex = -1;

        for (int i = 0; i < allCalls.size(); i++) {
            JobCall call = allCalls.get(i);
            String company = call.getCompanyName() != null ? call.getCompanyName() : "Call #" + call.getId();
            String label = "📌 " + company;
            if (call.getAppliedRole() != null && !call.getAppliedRole().isEmpty()) {
                label += " (" + call.getAppliedRole() + ")";
            }
            options.add(label);

            if (suggestedIndex == -1 && !company.trim().isEmpty()) {
                String domain = extractDomain(senderEmail);
                if (!domain.isEmpty() && company.toLowerCase().contains(domain)) {
                    suggestedIndex = i + 1;
                }
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1, options) {
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

        new AlertDialog.Builder(requireContext())
                .setTitle("📌 Assign Mail to Log\n" + email.getSubject())
                .setAdapter(adapter, (dialog, which) -> {
                    if (which == 0) {
                        JobCall newCall = new JobCall();
                        String companyName = email.getSenderDisplayName();
                        if (companyName.equalsIgnoreCase(email.getSenderEmail())) {
                            String domain = extractDomain(email.getSenderEmail());
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

                        Toast.makeText(requireContext(), "Created new log & attached email!", Toast.LENGTH_SHORT).show();
                        triggerAiEmailExtraction(newJobId, newCall, email);
                    } else {
                        JobCall chosenCall = allCalls.get(which - 1);
                        checkAutoInterviewInvite(chosenCall, email);
                        dbHelper.updateJobCall(chosenCall);

                        dbHelper.insertJobEmail(chosenCall.getId(), email.getGmailMessageId(), email.getSender(), email.getRecipient(), email.getSubject(), email.getSnippet(), email.getBody(), email.getReceivedTimestamp());

                        Toast.makeText(requireContext(), "Attached email to " + chosenCall.getCompanyName() + " log!", Toast.LENGTH_SHORT).show();
                        triggerAiEmailExtraction(chosenCall.getId(), chosenCall, email);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
                if (result == null || getContext() == null) return;
                String aiComp = result.optString("company_name", "").trim();
                String aiRole = result.optString("applied_role", "").trim();
                String aiRec = result.optString("recruiter_name", "").trim();
                String aiJd = result.optString("job_description", "").trim();
                org.json.JSONArray aiPoints = result.optJSONArray("key_discussion_points");

                boolean updated = false;
                if (!aiComp.isEmpty() && !"null".equalsIgnoreCase(aiComp) && (jobCall.getCompanyName() == null || jobCall.getCompanyName().isEmpty() || jobCall.getCompanyName().contains("@"))) {
                    jobCall.setCompanyName(aiComp);
                    updated = true;
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

                if (aiPoints != null && aiPoints.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < aiPoints.length(); i++) {
                        String pt = aiPoints.optString(i, "").trim();
                        if (!pt.isEmpty()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append("• ").append(pt);
                        }
                    }
                    if (sb.length() > 0) {
                        dbHelper.insertNote(targetJobId, sb.toString(), System.currentTimeMillis(), DatabaseHelper.NOTE_SOURCE_EMAIL);
                    }
                }
                if (getActivity() != null) {
                    requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "🤖 AI auto-filled details & summary notes from email!", Toast.LENGTH_SHORT).show()
                    );
                }
            }

            @Override
            public void onError(String error) {
            }
        });
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

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
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MailInboxActivity extends AppCompatActivity implements MailInboxAdapter.OnMailClickListener {

    private RecyclerView rvMailInbox;
    private MailInboxAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private EditText etSearch;
    private TextView tvAccountEmail;

    private DatabaseHelper dbHelper;
    private final List<EmailMessage> allFetchedEmails = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mail_inbox);

        dbHelper = new DatabaseHelper(this);

        Toolbar toolbar = findViewById(R.id.toolbar_mail_inbox);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        rvMailInbox = findViewById(R.id.rv_mail_inbox);
        swipeRefresh = findViewById(R.id.swipe_refresh_inbox);
        progressBar = findViewById(R.id.progress_inbox);
        tvEmpty = findViewById(R.id.tv_empty_inbox);
        etSearch = findViewById(R.id.et_search_inbox);
        tvAccountEmail = findViewById(R.id.tv_inbox_account_email);
        ImageView btnRefresh = findViewById(R.id.btn_refresh_inbox);

        String accountEmail = GmailService.getAccountEmail(this);
        if (tvAccountEmail != null) tvAccountEmail.setText(accountEmail);

        rvMailInbox.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MailInboxAdapter(this, this);
        rvMailInbox.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::loadInboxEmails);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> loadInboxEmails());
        }

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

        loadInboxEmails();
    }

    private void loadInboxEmails() {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);

        GmailService.fetchInboxMessagesAsync(this, 25, new GmailService.FetchCallback<List<EmailMessage>>() {
            @Override
            public void onSuccess(List<EmailMessage> result) {
                runOnUiThread(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);

                    allFetchedEmails.clear();
                    if (result != null) {
                        allFetchedEmails.addAll(result);
                    }

                    filterEmails(etSearch != null ? etSearch.getText().toString() : "");
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);

                    Toast.makeText(MailInboxActivity.this, "Could not load emails: " + error, Toast.LENGTH_LONG).show();
                    if (allFetchedEmails.isEmpty() && tvEmpty != null) {
                        tvEmpty.setText("⚠️ Failed to sync with Gmail.\nCheck credentials in Settings.");
                        tvEmpty.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void filterEmails(String query) {
        String q = query != null ? query.trim().toLowerCase() : "";
        List<EmailMessage> filtered = new ArrayList<>();

        for (EmailMessage mail : allFetchedEmails) {
            if (q.isEmpty()
                    || mail.getSubject().toLowerCase().contains(q)
                    || mail.getSender().toLowerCase().contains(q)
                    || mail.getSnippet().toLowerCase().contains(q)) {
                filtered.add(mail);
            }
        }

        adapter.setEmails(filtered);
        if (tvEmpty != null) {
            tvEmpty.setText(filtered.isEmpty() ? "No emails found matching '" + q + "'" : "");
            tvEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_mail_detail, null);
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

        if (tvSubject != null) tvSubject.setText(email.getSubject());
        if (tvFrom != null) tvFrom.setText("From: " + email.getSender());
        if (tvTo != null) tvTo.setText("To: " + (email.getRecipient().isEmpty() ? GmailService.getAccountEmail(this) : email.getRecipient()));

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

        if (body != null && (body.contains("<html") || body.contains("<div") || body.contains("<p>"))) {
            if (webView != null) {
                webView.setVisibility(View.VISIBLE);
                if (tvBody != null) tvBody.setVisibility(View.GONE);
                webView.loadData(body, "text/html; charset=utf-8", "UTF-8");
            } else if (tvBody != null) {
                tvBody.setText(Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY));
            }
        } else {
            if (tvBody != null) {
                tvBody.setText(body);
                tvBody.setVisibility(View.VISIBLE);
            }
            if (webView != null) webView.setVisibility(View.GONE);
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
        List<JobCall> allCalls = dbHelper.getAllJobCalls();

        List<String> options = new ArrayList<>();
        options.add("➕ Create New Call Log for this Email");

        // Try auto matching by sender name/domain
        String senderEmail = email.getSenderEmail().toLowerCase();
        int suggestedIndex = -1;

        for (int i = 0; i < allCalls.size(); i++) {
            JobCall call = allCalls.get(i);
            String company = call.getCompanyName() != null ? call.getCompanyName() : "Unknown Company";
            String label = "🏢 " + company + (call.getAppliedRole() != null && !call.getAppliedRole().isEmpty() ? " (" + call.getAppliedRole() + ")" : "");
            options.add(label);

            if (suggestedIndex == -1 && !company.trim().isEmpty()) {
                String domain = extractDomain(senderEmail);
                if (!domain.isEmpty() && company.toLowerCase().contains(domain)) {
                    suggestedIndex = i + 1; // +1 due to "Create New" item
                }
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, options) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                if (tv != null) {
                    tv.setTextColor(getResources().getColor(R.color.text_primary));
                    tv.setTextSize(14sp);
                    int pad = (int) (12 * getResources().getDisplayMetrics().density);
                    tv.setPadding(pad, pad, pad, pad);
                }
                return v;
            }
        };

        new AlertDialog.Builder(this)
                .setTitle("📌 Assign Mail to Log\n" + email.getSubject())
                .setAdapter(adapter, (dialog, which) -> {
                    if (which == 0) {
                        // Create New Job Call Log linked to email
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

                        long newJobId = dbHelper.insertJobCall(newCall);
                        dbHelper.insertJobEmail(newJobId, email.getGmailMessageId(), email.getSender(), email.getRecipient(), email.getSubject(), email.getSnippet(), email.getBody(), email.getReceivedTimestamp());

                        Toast.makeText(MailInboxActivity.this, "Created new log & attached email!", Toast.LENGTH_SHORT).show();
                    } else {
                        // Assign to existing call log
                        JobCall chosenCall = allCalls.get(which - 1);
                        dbHelper.insertJobEmail(chosenCall.getId(), email.getGmailMessageId(), email.getSender(), email.getRecipient(), email.getSubject(), email.getSnippet(), email.getBody(), email.getReceivedTimestamp());

                        Toast.makeText(MailInboxActivity.this, "Attached email to " + chosenCall.getCompanyName() + " log!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

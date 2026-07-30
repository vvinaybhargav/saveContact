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

                    new AlertDialog.Builder(MailInboxActivity.this)
                            .setTitle("⚠️ Gmail Sync Error")
                            .setMessage(error)
                            .setPositiveButton("OK", null)
                            .show();

                    if (allFetchedEmails.isEmpty() && tvEmpty != null) {
                        tvEmpty.setText("⚠️ Failed to sync with Gmail.\n" + error);
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
                    || mail.getRecipient().toLowerCase().contains(q)
                    || mail.getSnippet().toLowerCase().contains(q)
                    || mail.getBody().toLowerCase().contains(q)) {
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
    }

    private void showAssignMailDialog(EmailMessage email) {
        if (dbHelper == null) return;

        List<AssignOption> assignOptions = new ArrayList<>();
        List<JobCall> allCalls = dbHelper.getAllJobCalls();
        String domain = extractDomain(email.getSenderEmail());

        // 1. Check existing logged calls
        for (JobCall call : allCalls) {
            String company = call.getCompanyName() != null ? call.getCompanyName() : "Call #" + call.getId();
            boolean matchesDomain = !domain.isEmpty() && company.toLowerCase().contains(domain);
            
            AssignOption opt = new AssignOption();
            opt.existingCall = call;
            opt.label = (matchesDomain ? "⭐ RECOMMENDED: " : "📌 ") + company;
            if (call.getAppliedRole() != null && !call.getAppliedRole().isEmpty()) {
                opt.label += " (" + call.getAppliedRole() + ")";
            }
            if (call.getPhoneNumber() != null && !call.getPhoneNumber().isEmpty()) {
                opt.label += " - " + call.getPhoneNumber();
            }
            if (matchesDomain) {
                assignOptions.add(0, opt);
            } else {
                assignOptions.add(opt);
            }
        }

        // 2. Check unlogged recent calls from system phone history
        List<String> unloggedPhones = getUnloggedPhoneNumbers(this);
        for (String phone : unloggedPhones) {
            AssignOption opt = new AssignOption();
            opt.unloggedPhone = phone;
            opt.label = "➕ Create Log for Recent Call: " + phone;
            assignOptions.add(opt);
        }

        // 3. Option for blank new log
        AssignOption blankOpt = new AssignOption();
        blankOpt.label = "➕ Create New Blank Log";
        assignOptions.add(blankOpt);

        List<String> labels = new ArrayList<>();
        for (AssignOption opt : assignOptions) {
            labels.add(opt.label);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
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

        new AlertDialog.Builder(this)
                .setTitle("📌 Assign Mail to Log\n" + email.getSubject())
                .setAdapter(adapter, (dialog, which) -> {
                    AssignOption selected = assignOptions.get(which);
                    if (selected.existingCall != null) {
                        // Attach to existing log
                        JobCall chosenCall = selected.existingCall;
                        dbHelper.insertJobEmail(chosenCall.getId(), email.getGmailMessageId(), email.getSender(), email.getRecipient(), email.getSubject(), email.getSnippet(), email.getBody(), email.getReceivedTimestamp());
                        Toast.makeText(MailInboxActivity.this, "Attached email to " + chosenCall.getCompanyName() + " log!", Toast.LENGTH_SHORT).show();
                        triggerAiEmailExtraction(chosenCall.getId(), chosenCall, email);
                    } else {
                        // Create new log (either from unlogged phone number or blank)
                        JobCall newCall = new JobCall();
                        if (selected.unloggedPhone != null) {
                            newCall.setPhoneNumber(selected.unloggedPhone);
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

                        long newJobId = dbHelper.insertJobCall(newCall);
                        dbHelper.insertJobEmail(newJobId, email.getGmailMessageId(), email.getSender(), email.getRecipient(), email.getSubject(), email.getSnippet(), email.getBody(), email.getReceivedTimestamp());

                        Toast.makeText(MailInboxActivity.this, "Created new log" + (selected.unloggedPhone != null ? " for " + selected.unloggedPhone : "") + " & attached email!", Toast.LENGTH_SHORT).show();
                        triggerAiEmailExtraction(newJobId, newCall, email);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    private void triggerAiEmailExtraction(long targetJobId, JobCall jobCall, EmailMessage email) {
        OpenAiClient.extractFieldsFromEmail(this, email.getSubject(), email.getBody(), new OpenAiClient.OpenAiCallback() {
            @Override
            public void onSuccess(org.json.JSONObject result) {
                if (result == null) return;
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
                        runOnUiThread(() -> {
                            new AlertDialog.Builder(MailInboxActivity.this)
                                    .setTitle("⚠️ Company Name Difference Detected")
                                    .setMessage("Note/Log says: \"" + existingComp + "\"\nEmail extracted: \"" + newComp + "\"\n\nWhich format would you like to use?")
                                    .setPositiveButton("Use \"" + newComp + "\"", (dialog, which) -> {
                                        jobCall.setCompanyName(newComp);
                                        dbHelper.updateJobCall(jobCall);
                                        Toast.makeText(MailInboxActivity.this, "Updated company to " + newComp, Toast.LENGTH_SHORT).show();
                                    })
                                    .setNeutralButton("Combine: \"" + combinedComp + "\"", (dialog, which) -> {
                                        jobCall.setCompanyName(combinedComp);
                                        dbHelper.updateJobCall(jobCall);
                                        Toast.makeText(MailInboxActivity.this, "Updated company to " + combinedComp, Toast.LENGTH_SHORT).show();
                                    })
                                    .setNegativeButton("Keep \"" + existingComp + "\"", null)
                                    .show();
                        });
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
                Toast.makeText(MailInboxActivity.this, "🤖 AI auto-filled details & summary notes from email!", Toast.LENGTH_SHORT).show();
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

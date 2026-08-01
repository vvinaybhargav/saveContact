package com.example.callsaver;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class JobCallAdapter extends RecyclerView.Adapter<JobCallAdapter.ViewHolder> {

    private final List<JobCall> callList;
    private final Context context;
    private final OnItemClickListener listener;
    private java.util.Map<String, Integer> companyGroupSizes = new java.util.HashMap<>();
    private java.util.Map<String, List<JobCall>> companyGroupMembers = new java.util.HashMap<>();
    private java.util.Set<String> expandedGroupCompanies = new java.util.HashSet<>();
    private List<Boolean> headerFlags = new java.util.ArrayList<>();

    public interface OnItemClickListener {
        void onItemClick(JobCall jobCall);
        void onFollowUpClick(JobCall jobCall);
        void onViewEmailsClick(JobCall jobCall);
    }

    /** Optional: implement on the OnItemClickListener to rebuild the grouped list after a toggle. */
    public interface OnGroupToggleListener {
        void onGroupToggled();
    }

    /** Optional: implement to reload from the DB after a company-wide status bulk-edit. */
    public interface OnCompanyBulkEditListener {
        void onCompanyBulkEdit();
    }

    public JobCallAdapter(Context context, List<JobCall> callList, OnItemClickListener listener) {
        this.context = context;
        this.callList = callList;
        this.listener = listener;
    }

    /** Called by TrackerFragment after each list rebuild to update company-group badges/toggle state. */
    public void setCompanyGroups(java.util.Map<String, Integer> groupSizes, java.util.Map<String, List<JobCall>> groupMembers,
                                  java.util.Set<String> expandedCompanies, List<Boolean> headerFlags) {
        this.companyGroupSizes = groupSizes;
        this.companyGroupMembers = groupMembers;
        this.expandedGroupCompanies = expandedCompanies;
        this.headerFlags = headerFlags;
    }

    private String normalizeCompanyKey(String companyName) {
        return companyName == null ? "" : companyName.trim().toLowerCase(Locale.getDefault());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_job_call, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        JobCall call = callList.get(position);
        final String groupKeyForRow = call.getId() > 0 ? normalizeCompanyKey(call.getCompanyName()) : "";
        final boolean isGroupHeaderRow = position < headerFlags.size() && Boolean.TRUE.equals(headerFlags.get(position));

        if (isGroupHeaderRow) {
            bindHeaderRow(holder, call, groupKeyForRow);
            return;
        }

        // Undo bindHeaderRow's hiding/recoloring, in case this ViewHolder is a recycled header row.
        holder.tvPhoneNumber.setVisibility(View.VISIBLE);
        holder.tvPhoneNumber.setTextColor(context.getResources().getColor(R.color.text_secondary));
        holder.tvStatusBadge.setVisibility(View.VISIBLE);
        if (holder.rowFooter != null) holder.rowFooter.setVisibility(View.VISIBLE);

        if (call.getId() <= 0) {
            // Unlogged Call Design
            String displayName = call.getCompanyName();
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = "Unlogged Call";
            }
            holder.tvCompanyName.setText(displayName);
            holder.tvPhoneNumber.setText(call.getPhoneNumber());

            int duration = call.getDuration();
            String durationStr = duration + "s";
            if (duration >= 60) {
                durationStr = (duration / 60) + "m " + (duration % 60) + "s";
            }
            holder.tvTags.setVisibility(View.VISIBLE);
            holder.tvTags.setText("Duration: " + durationStr);

            holder.tvNotesPreview.setVisibility(View.VISIBLE);
            holder.tvNotesPreview.setText(call.getNotes()); // e.g. "Incoming Call" / "Outgoing Call"

            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
            String formattedDate = sdf.format(new Date(call.getTimestamp()));
            holder.tvCallTime.setText(formattedDate);

            holder.tvAvatarText.setText("?");
            holder.cardAvatar.setCardBackgroundColor(0xFF708090); // Slate gray

            holder.parentCard.setCardBackgroundColor(context.getResources().getColor(R.color.white));
            holder.parentCard.setStrokeColor(context.getResources().getColor(R.color.divider));
            holder.parentCard.setStrokeWidth(1);

            if (holder.btnActionFollowup != null) {
                holder.btnActionFollowup.setVisibility(View.GONE);
            }

            setupStatusBadge(holder.tvStatusBadge, call);
        } else {
            // Tracked Call Design
            holder.parentCard.setCardBackgroundColor(context.getResources().getColor(R.color.white));
            holder.parentCard.setStrokeColor(context.getResources().getColor(R.color.divider));
            holder.parentCard.setStrokeWidth(1);
            if (holder.btnActionFollowup != null) {
                holder.btnActionFollowup.setVisibility(View.VISIBLE);
            }

            String company = call.getCompanyName();
            String recruiter = call.getRecruiterName();
            String displayCompany;
            if (company != null && !company.trim().isEmpty() && recruiter != null && !recruiter.trim().isEmpty()) {
                displayCompany = recruiter.trim() + " @ " + company.trim();
            } else if (company != null && !company.trim().isEmpty()) {
                displayCompany = company.trim();
            } else if (recruiter != null && !recruiter.trim().isEmpty()) {
                displayCompany = recruiter.trim();
            } else {
                displayCompany = call.getPhoneNumber();
            }
            holder.tvCompanyName.setText(displayCompany);
            holder.tvPhoneNumber.setText(call.getPhoneNumber());

            String tags = call.getTags();
            if (tags == null || tags.trim().isEmpty()) {
                holder.tvTags.setVisibility(View.GONE);
            } else {
                holder.tvTags.setVisibility(View.VISIBLE);
                holder.tvTags.setText("Tags: " + tags);
            }

            String notes = call.getNotes();
            if (notes == null || notes.trim().isEmpty()) {
                holder.tvNotesPreview.setVisibility(View.GONE);
            } else {
                holder.tvNotesPreview.setVisibility(View.VISIBLE);
                holder.tvNotesPreview.setText("Notes: " + notes);
            }

            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
            String formattedDate = sdf.format(new Date(call.getLastActivityTime()));
            holder.tvCallTime.setText(formattedDate);

            String companyForInitial = (company == null || company.trim().isEmpty()) ? "" : company;
            String initial = companyForInitial.isEmpty() ? "?" : String.valueOf(companyForInitial.charAt(0)).toUpperCase();
            holder.tvAvatarText.setText(initial);

            int[] avatarColors = {0xFF6E6E76, 0xFF10B981, 0xFF3B82F6, 0xFF64748B, 0xFFEC4899, 0xFFF59E0B, 0xFF14B8A6};
            int colorIndex = Math.abs(displayCompany.hashCode()) % avatarColors.length;
            holder.cardAvatar.setCardBackgroundColor(avatarColors[colorIndex]);

            setupStatusBadge(holder.tvStatusBadge, call);
        }

        // Direct Call back Action
        holder.btnActionCall.setOnClickListener(v -> {
            callDirectly(call.getPhoneNumber());
        });

        // Attached Emails Action
        if (holder.btnActionEmails != null) {
            if (call.getId() > 0) {
                DatabaseHelper db = new DatabaseHelper(context);
                List<EmailMessage> emails = db.getEmailsForJob(call.getId());
                if (emails != null && !emails.isEmpty()) {
                    holder.btnActionEmails.setVisibility(View.VISIBLE);
                    if (holder.tvEmailBadgeCount != null) {
                        holder.tvEmailBadgeCount.setText("📧 " + emails.size());
                    }
                    holder.btnActionEmails.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onViewEmailsClick(call);
                        }
                    });
                } else {
                    holder.btnActionEmails.setVisibility(View.GONE);
                }
            } else {
                holder.btnActionEmails.setVisibility(View.GONE);
            }
        }

        // WhatsApp Action
        if (holder.btnActionFollowup != null) {
            holder.btnActionFollowup.setOnClickListener(v -> {
                String number = call.getPhoneNumber();
                if (number != null && !number.trim().isEmpty()) {
                    String cleanNum = number.replaceAll("[^0-9+]", "");
                    try {
                        String url = "https://api.whatsapp.com/send?phone=" + cleanNum;
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        context.startActivity(i);
                    } catch (Exception e) {
                        android.widget.Toast.makeText(context, "WhatsApp is not installed", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        // Card Click Action
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(call);
            }
        });
    }

    /** Minimal collapsed/expanded company row: just the company name, count, and a chevron - tap toggles. */
    private void bindHeaderRow(ViewHolder holder, JobCall call, String groupKey) {
        int size = companyGroupSizes.containsKey(groupKey) ? companyGroupSizes.get(groupKey) : 1;
        boolean expanded = expandedGroupCompanies.contains(groupKey);
        String company = call.getCompanyName();
        String displayCompany = (company == null || company.trim().isEmpty()) ? call.getPhoneNumber() : company.trim();
        holder.tvCompanyName.setText(displayCompany + "   " + size + (size == 1 ? " lead" : " leads") + "  " + (expanded ? "▾" : "▸"));

        // tv_status_badge sits inline next to the company name in this layout (by design,
        // for the normal full-card row) - for the header row we want status+feedback on
        // their OWN line below the name, so it's always hidden here and tv_phone_number
        // (which IS on its own full-width line, and unused by header rows otherwise) is
        // repurposed to show it instead.
        holder.tvStatusBadge.setVisibility(View.GONE);
        if (holder.rowFooter != null) holder.rowFooter.setVisibility(View.GONE);

        // Company-level note is independent context; the status line instead reflects
        // the leads' own round_status/feedback - if they all currently agree, that shared
        // status shows here, so editing/cascading it never lets this line drift out of
        // sync with the actual leads underneath.
        DatabaseHelper.CompanyMeta meta = new DatabaseHelper(context).getCompanyMeta(groupKey);
        if (meta.note != null && !meta.note.trim().isEmpty()) {
            holder.tvNotesPreview.setVisibility(View.VISIBLE);
            holder.tvNotesPreview.setText(meta.note.trim());
        } else {
            holder.tvNotesPreview.setVisibility(View.GONE);
        }
        // Status and feedback shown side by side on one line, below the company name,
        // when the leads agree on either (e.g. "1st Round  ·  Scheduled").
        String commonStatus = commonStatusForGroup(groupKey);
        String commonFeedback = commonFeedbackForGroup(groupKey);
        if (commonStatus != null || commonFeedback != null) {
            String combined = commonStatus != null ? commonStatus : "";
            if (commonFeedback != null) {
                combined += combined.isEmpty() ? commonFeedback : "  ·  " + commonFeedback;
            }
            holder.tvPhoneNumber.setVisibility(View.VISIBLE);
            holder.tvPhoneNumber.setText(combined);
            holder.tvPhoneNumber.setTextColor(0xFFC7C7CC);
        } else {
            holder.tvPhoneNumber.setVisibility(View.GONE);
        }
        String commonNextCall = commonNextCallForGroup(groupKey);
        if (commonNextCall != null) {
            holder.tvTags.setVisibility(View.VISIBLE);
            holder.tvTags.setText("Next call: " + commonNextCall);
        } else {
            holder.tvTags.setVisibility(View.GONE);
        }

        String initial = displayCompany.isEmpty() ? "?" : String.valueOf(displayCompany.charAt(0)).toUpperCase();
        holder.tvAvatarText.setText(initial);
        int[] avatarColors = {0xFF6E6E76, 0xFF10B981, 0xFF3B82F6, 0xFF64748B, 0xFFEC4899, 0xFFF59E0B, 0xFF14B8A6};
        holder.cardAvatar.setCardBackgroundColor(avatarColors[Math.abs(displayCompany.hashCode()) % avatarColors.length]);

        holder.parentCard.setCardBackgroundColor(context.getResources().getColor(R.color.white));
        holder.parentCard.setStrokeColor(context.getResources().getColor(R.color.divider));
        holder.parentCard.setStrokeWidth(1);

        holder.itemView.setOnClickListener(v -> {
            if (!expandedGroupCompanies.remove(groupKey)) {
                expandedGroupCompanies.add(groupKey);
            }
            if (listener instanceof OnGroupToggleListener) {
                ((OnGroupToggleListener) listener).onGroupToggled();
            } else {
                notifyDataSetChanged();
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            showCompanyMetaEditDialog(groupKey, displayCompany, meta);
            return true;
        });
    }

    /** Null unless every lead in this company group currently shares the same round_status. */
    private String commonStatusForGroup(String groupKey) {
        List<JobCall> members = companyGroupMembers.get(groupKey);
        if (members == null || members.isEmpty()) return null;
        String first = members.get(0).getRoundStatus();
        if (first == null || first.trim().isEmpty()) return null;
        for (JobCall m : members) {
            if (!first.equals(m.getRoundStatus())) return null;
        }
        return first;
    }

    /** Null unless every lead in this company group currently shares the same next-call date/time. */
    private String commonNextCallForGroup(String groupKey) {
        List<JobCall> members = companyGroupMembers.get(groupKey);
        if (members == null || members.isEmpty()) return null;
        String first = members.get(0).getTentativeSchedule();
        if (first == null || first.trim().isEmpty()) return null;
        for (JobCall m : members) {
            if (!first.equals(m.getTentativeSchedule())) return null;
        }
        return first;
    }

    /**
     * Long-press on a company header: edit its persistent note, and/or bulk-assign a round
     * status to every lead in the company (e.g. "BGV called, move the whole TCS group to
     * Final Round") - this writes straight to each lead's own round_status rather than a
     * separate company-only field, so it can never drift out of sync with the leads.
     */
    private void showCompanyMetaEditDialog(String groupKey, String displayCompany, DatabaseHelper.CompanyMeta meta) {
        android.widget.LinearLayout container = new android.widget.LinearLayout(context);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        int gap = (int) (14 * context.getResources().getDisplayMetrics().density);
        container.setPadding(pad, (int) (8 * context.getResources().getDisplayMetrics().density), pad, 0);

        android.widget.TextView tvSubtitle = new android.widget.TextView(context);
        tvSubtitle.setText("Applies to every lead at this company");
        tvSubtitle.setTextColor(0xFF9C9CA3);
        tvSubtitle.setTextSize(12);
        tvSubtitle.setPadding(0, 0, 0, gap);
        container.addView(tvSubtitle);

        final android.widget.EditText etNote = new android.widget.EditText(context);
        etNote.setHint("Note");
        if (meta.note != null) etNote.setText(meta.note);
        container.addView(etNote);
        container.addView(fieldLabel("Status", gap));

        String[] statuses = context.getResources().getStringArray(R.array.round_statuses);
        String[] statusOptions = new String[statuses.length + 1];
        statusOptions[0] = "(leave unchanged)";
        System.arraycopy(statuses, 0, statusOptions, 1, statuses.length);
        final android.widget.Spinner spinner = new android.widget.Spinner(context);
        android.widget.ArrayAdapter<String> spinnerAdapter = new android.widget.ArrayAdapter<>(
                context, android.R.layout.simple_spinner_item, statusOptions);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);
        String currentCommon = commonStatusForGroup(groupKey);
        if (currentCommon != null) {
            int idx = spinnerAdapter.getPosition(currentCommon);
            if (idx >= 0) spinner.setSelection(idx);
        }
        container.addView(spinner);
        container.addView(fieldLabel("Feedback", gap));

        String[] feedbackOptions = {"(leave unchanged)", "Feedback Pending", "Scheduled", "Yet to Schedule", "Interested", "Not Interested", "Negative"};
        final android.widget.Spinner feedbackSpinner = new android.widget.Spinner(context);
        android.widget.ArrayAdapter<String> feedbackAdapter = new android.widget.ArrayAdapter<>(
                context, android.R.layout.simple_spinner_item, feedbackOptions);
        feedbackAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        feedbackSpinner.setAdapter(feedbackAdapter);
        String currentFeedback = commonFeedbackForGroup(groupKey);
        if (currentFeedback != null) {
            int idx = feedbackAdapter.getPosition(currentFeedback);
            if (idx >= 0) feedbackSpinner.setSelection(idx);
        }
        container.addView(feedbackSpinner);
        container.addView(fieldLabel("Next call", gap));

        // Same tap-to-open date/time picker as the individual lead's "Next Call" field,
        // rather than free text - and the same underlying column, so a bulk-set date
        // shows up in Upcoming Interviews and on every lead just like setting it
        // individually would.
        final android.widget.EditText etNextCall = new android.widget.EditText(context);
        etNextCall.setHint("Tap to pick date & time");
        etNextCall.setFocusable(false);
        etNextCall.setClickable(true);
        String currentNext = commonNextCallForGroup(groupKey);
        if (currentNext != null) etNextCall.setText(currentNext);
        etNextCall.setOnClickListener(v -> showDateTimePicker(etNextCall));
        container.addView(etNextCall);

        android.widget.Space bottomSpace = new android.widget.Space(context);
        bottomSpace.setLayoutParams(new android.widget.LinearLayout.LayoutParams(1, gap));
        container.addView(bottomSpace);

        // Wrapped in a ScrollView: with note + status + feedback + next-call fields
        // stacked, this can exceed screen height on smaller phones and push the Save
        // button off-screen, making it look like saving silently does nothing when it's
        // actually unreachable.
        android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        scrollView.addView(container);

        new androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(displayCompany)
                .setView(scrollView)
                .setPositiveButton("Save", (dialog, which) -> {
                    String note = etNote.getText().toString().trim();
                    DatabaseHelper db = new DatabaseHelper(context);
                    db.upsertCompanyMeta(groupKey, note.isEmpty() ? null : note, null);
                    boolean bulkChanged = false;
                    if (spinner.getSelectedItemPosition() > 0) {
                        String selectedStatus = (String) spinner.getSelectedItem();
                        db.applyStatusToAllCompanyLeads(groupKey, selectedStatus);
                        bulkChanged = true;
                    }
                    if (feedbackSpinner.getSelectedItemPosition() > 0) {
                        String selectedFeedback = (String) feedbackSpinner.getSelectedItem();
                        db.applyFeedbackToAllCompanyLeads(groupKey, selectedFeedback);
                        bulkChanged = true;
                    }
                    String nextCall = etNextCall.getText().toString().trim();
                    if (!nextCall.isEmpty()) {
                        db.applyNextCallToAllCompanyLeads(groupKey, nextCall);
                        bulkChanged = true;
                    }
                    if (bulkChanged && listener instanceof OnCompanyBulkEditListener) {
                        ((OnCompanyBulkEditListener) listener).onCompanyBulkEdit();
                        return;
                    }
                    notifyDataSetChanged();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Small compact section label, used to keep the company edit dialog free of repeated long sentences. */
    private android.widget.TextView fieldLabel(String text, int topMargin) {
        android.widget.TextView tv = new android.widget.TextView(context);
        tv.setText(text);
        tv.setTextColor(0xFF9C9CA3);
        tv.setTextSize(11);
        tv.setPadding(0, topMargin, 0, (int) (4 * context.getResources().getDisplayMetrics().density));
        return tv;
    }

    /** Same date/time picker flow as InCallActivity's "Next Call" field. */
    private void showDateTimePicker(android.widget.EditText etTarget) {
        final java.util.Calendar calendar = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(context, (view, year, month, dayOfMonth) -> {
            calendar.set(java.util.Calendar.YEAR, year);
            calendar.set(java.util.Calendar.MONTH, month);
            calendar.set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth);

            new android.app.TimePickerDialog(context, (timeView, hourOfDay, minute) -> {
                calendar.set(java.util.Calendar.HOUR_OF_DAY, hourOfDay);
                calendar.set(java.util.Calendar.MINUTE, minute);
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd 'at' hh:mm a", Locale.getDefault());
                etTarget.setText(format.format(calendar.getTime()));
            }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), false).show();
        }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    /** Null unless every lead in this company group currently shares the same feedback (interest_rating). */
    private String commonFeedbackForGroup(String groupKey) {
        List<JobCall> members = companyGroupMembers.get(groupKey);
        if (members == null || members.isEmpty()) return null;
        String first = members.get(0).getInterestRating();
        if (first == null || first.trim().isEmpty()) return null;
        for (JobCall m : members) {
            if (!first.equals(m.getInterestRating())) return null;
        }
        return first;
    }

    private void setupStatusBadge(TextView tv, JobCall call) {
        if (call.getId() <= 0) {
            String status = call.getRoundStatus();
            tv.setText(status);
            
            int textColor;
            int bgColor = context.getResources().getColor(R.color.status_warning_bg);
            
            if ("Outgoing".equalsIgnoreCase(status)) {
                textColor = context.getResources().getColor(R.color.status_green);
            } else if ("Missed".equalsIgnoreCase(status) || "Rejected".equalsIgnoreCase(status)) {
                textColor = context.getResources().getColor(R.color.status_error);
            } else {
                textColor = context.getResources().getColor(R.color.status_warning);
            }
            
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(bgColor);
            float density = context.getResources().getDisplayMetrics().density;
            gd.setCornerRadius(10 * density);
            tv.setBackground(gd);
            tv.setTextColor(textColor);
            return;
        }

        String status = call.getRoundStatus();
        if (status == null) {
            status = "First time";
        }
        String badgeText = status;
        if (call.getInterestRating() != null && !call.getInterestRating().isEmpty()) {
            badgeText += " (" + call.getInterestRating() + ")";
        }
        tv.setText(badgeText);
        applyStatusColors(tv, status);
    }

    /** Shared status → color mapping, used for both per-lead and company-level status badges. */
    private void applyStatusColors(TextView tv, String status) {
        int textColor;
        int bgColor;

        switch (status) {
            case "Negative":
            case "Not Interested":
                textColor = context.getResources().getColor(R.color.status_error);
                bgColor = context.getResources().getColor(R.color.status_red_bg);
                break;
            case "Offered":
                textColor = context.getResources().getColor(R.color.status_green);
                bgColor = context.getResources().getColor(R.color.status_green_bg);
                break;
            case "1st Round":
            case "2nd Round":
                textColor = context.getResources().getColor(R.color.status_purple);
                bgColor = context.getResources().getColor(R.color.status_purple_bg);
                break;
            case "Final Round":
            case "HR / Salary":
                textColor = context.getResources().getColor(R.color.status_green);
                bgColor = context.getResources().getColor(R.color.status_green_bg);
                break;
            case "First time":
            case "Screening":
            case "Interested":
            default:
                textColor = context.getResources().getColor(R.color.status_blue);
                bgColor = context.getResources().getColor(R.color.status_blue_bg);
                break;
        }

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        float density = context.getResources().getDisplayMetrics().density;
        gd.setCornerRadius(10 * density); // 10dp radius
        tv.setBackground(gd);
        tv.setTextColor(textColor);
    }

    private void callDirectly(String number) {
        // ACTION_DIAL just opens a dialer app to pre-fill the number - since CallSaver
        // IS the default dialer, that resolves back to this app's own Recents tab
        // instead of placing the call. Place it directly via Telecom instead, same as
        // RecentsFragment.onDialClick.
        Uri uri = Uri.fromParts("tel", number.trim(), null);
        CallReceiver.recordOutgoingNumber(context, number);
        boolean canCall = androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (canCall) {
            try {
                android.telecom.TelecomManager tm = (android.telecom.TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    tm.placeCall(uri, null);
                    return;
                }
            } catch (SecurityException e) {
                e.printStackTrace();
            }
            try {
                Intent call = new Intent(Intent.ACTION_CALL, uri);
                call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(call);
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try {
            Intent dialIntent = new Intent(Intent.ACTION_DIAL, uri);
            dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(dialIntent);
        } catch (Exception e) {
            android.widget.Toast.makeText(context, "Cannot open dialer: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return callList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvCompanyName, tvPhoneNumber, tvTags, tvNotesPreview, tvCallTime, tvAvatarText, tvStatusBadge, tvEmailBadgeCount;
        MaterialCardView cardAvatar, btnActionCall, btnActionFollowup, btnActionEmails, parentCard;
        View rowFooter;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            parentCard = (MaterialCardView) itemView;
            tvCompanyName = itemView.findViewById(R.id.tv_company_name);
            tvPhoneNumber = itemView.findViewById(R.id.tv_phone_number);
            tvTags = itemView.findViewById(R.id.tv_tags);
            tvNotesPreview = itemView.findViewById(R.id.tv_notes_preview);
            tvCallTime = itemView.findViewById(R.id.tv_call_time);
            tvAvatarText = itemView.findViewById(R.id.tv_avatar_text);
            tvStatusBadge = itemView.findViewById(R.id.tv_status_badge);
            tvEmailBadgeCount = itemView.findViewById(R.id.tv_email_badge_count);
            cardAvatar = itemView.findViewById(R.id.card_avatar);
            btnActionCall = itemView.findViewById(R.id.btn_action_call);
            btnActionFollowup = itemView.findViewById(R.id.btn_action_followup);
            btnActionEmails = itemView.findViewById(R.id.btn_action_emails);
            rowFooter = itemView.findViewById(R.id.row_footer);
        }
    }
}

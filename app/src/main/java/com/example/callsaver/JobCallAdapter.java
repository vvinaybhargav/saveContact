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

    public JobCallAdapter(Context context, List<JobCall> callList, OnItemClickListener listener) {
        this.context = context;
        this.callList = callList;
        this.listener = listener;
    }

    /** Called by TrackerFragment after each list rebuild to update company-group badges/toggle state. */
    public void setCompanyGroups(java.util.Map<String, Integer> groupSizes, java.util.Set<String> expandedCompanies, List<Boolean> headerFlags) {
        this.companyGroupSizes = groupSizes;
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

        // Undo bindHeaderRow's hiding, in case this ViewHolder is a recycled header row.
        holder.tvPhoneNumber.setVisibility(View.VISIBLE);
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

        holder.tvPhoneNumber.setVisibility(View.GONE);
        holder.tvTags.setVisibility(View.GONE);
        holder.tvNotesPreview.setVisibility(View.GONE);
        holder.tvStatusBadge.setVisibility(View.GONE);
        if (holder.rowFooter != null) holder.rowFooter.setVisibility(View.GONE);

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

        // Apply rounded corner programmatically to status badge
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

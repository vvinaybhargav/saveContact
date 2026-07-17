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

    public interface OnItemClickListener {
        void onItemClick(JobCall jobCall);
        void onFollowUpClick(JobCall jobCall);
    }

    public JobCallAdapter(Context context, List<JobCall> callList, OnItemClickListener listener) {
        this.context = context;
        this.callList = callList;
        this.listener = listener;
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

        if (call.getId() <= 0) {
            // Unlogged Call Design
            holder.tvCompanyName.setText("Unlogged Call");
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

            holder.parentCard.setCardBackgroundColor(context.getResources().getColor(R.color.bg_light));
            holder.parentCard.setStrokeColor(0xFFFF8C00); // Orange border
            holder.parentCard.setStrokeWidth(2);

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
            String formattedDate = sdf.format(new Date(call.getTimestamp()));
            holder.tvCallTime.setText(formattedDate);

            String companyForInitial = (company == null || company.trim().isEmpty()) ? "" : company;
            String initial = companyForInitial.isEmpty() ? "?" : String.valueOf(companyForInitial.charAt(0)).toUpperCase();
            holder.tvAvatarText.setText(initial);

            int[] avatarColors = {0xFF6366F1, 0xFF10B981, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFFF59E0B, 0xFF14B8A6};
            int colorIndex = Math.abs(displayCompany.hashCode()) % avatarColors.length;
            holder.cardAvatar.setCardBackgroundColor(avatarColors[colorIndex]);

            setupStatusBadge(holder.tvStatusBadge, call);
        }

        // Direct Call back Action
        holder.btnActionCall.setOnClickListener(v -> {
            callDirectly(call.getPhoneNumber());
        });

        // Follow Up Action
        if (holder.btnActionFollowup != null) {
            holder.btnActionFollowup.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onFollowUpClick(call);
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

    private void setupStatusBadge(TextView tv, JobCall call) {
        if (call.getId() <= 0) {
            tv.setText("Unlogged");
            int textColor = context.getResources().getColor(R.color.status_error);
            int bgColor = context.getResources().getColor(R.color.status_red_bg);
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
            badgeText += " (" + call.getInterestRating() + "/10)";
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
        try {
            Intent dialIntent = new Intent(Intent.ACTION_DIAL);
            dialIntent.setData(Uri.parse("tel:" + number.trim()));
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
        TextView tvCompanyName, tvPhoneNumber, tvTags, tvNotesPreview, tvCallTime, tvAvatarText, tvStatusBadge;
        MaterialCardView cardAvatar, btnActionCall, btnActionFollowup, parentCard;

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
            cardAvatar = itemView.findViewById(R.id.card_avatar);
            btnActionCall = itemView.findViewById(R.id.btn_action_call);
            btnActionFollowup = itemView.findViewById(R.id.btn_action_followup);
        }
    }
}

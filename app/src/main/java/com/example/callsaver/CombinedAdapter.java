package com.example.callsaver;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CombinedAdapter extends RecyclerView.Adapter<CombinedAdapter.ViewHolder> {

    private final List<CombinedCallModel> callList;
    private final Context context;
    private final OnCombinedActionListener listener;

    public interface OnCombinedActionListener {
        void onDialClick(String phoneNumber);
        void onItemClick(CombinedCallModel item);
    }

    public static class CombinedCallModel {
        public String number;
        public String name;
        public int type;
        public long date;
        public String sim;
        public int duration;
        public JobCall jobCall;

        public CombinedCallModel(String number, String name, int type, long date, String sim, int duration, JobCall jobCall) {
            this.number = number;
            this.name = name;
            this.type = type;
            this.date = date;
            this.sim = sim;
            this.duration = duration;
            this.jobCall = jobCall;
        }
    }

    public CombinedAdapter(Context context, List<CombinedCallModel> callList, OnCombinedActionListener listener) {
        this.context = context;
        this.callList = callList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_combined_call, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CombinedCallModel item = callList.get(position);

        // Determine title: Recruiter @ Company > Company > Recruiter > Contact Name > Number
        String displayName = item.number;
        boolean isTracked = item.jobCall != null && item.jobCall.getId() > 0;

        if (isTracked) {
            String comp = item.jobCall.getCompanyName();
            String rec = item.jobCall.getRecruiterName();
            if (comp != null && !comp.trim().isEmpty() && rec != null && !rec.trim().isEmpty()) {
                displayName = rec.trim() + " @ " + comp.trim();
            } else if (comp != null && !comp.trim().isEmpty()) {
                displayName = comp.trim();
            } else if (rec != null && !rec.trim().isEmpty()) {
                displayName = rec.trim();
            } else if (item.name != null && !item.name.trim().isEmpty()) {
                displayName = item.name;
            }
        } else if (item.name != null && !item.name.trim().isEmpty()) {
            displayName = item.name;
        }
        holder.tvCallerTitle.setText(displayName);
        holder.tvPhoneNumber.setText(item.number);

        // Format Date Time and duration
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault());
        String dateStr = sdf.format(new Date(item.date));
        String simSuffix = (item.sim != null && !item.sim.isEmpty()) ? " · " + item.sim : "";
        
        String durationStr = "";
        if (item.duration > 0) {
            durationStr = " · " + String.format(Locale.getDefault(), "%d:%02d", item.duration / 60, item.duration % 60);
        }
        holder.tvCallDetails.setText(dateStr + durationStr + simSuffix);

        // Style the call type icon
        setupCallTypeStyle(holder, item.type);

        // Configure tracked fields
        if (isTracked) {
            holder.tvTrackedStatus.setText("Tracked recruiter · tap to edit log");
            holder.tvTrackedStatus.setTextColor(ContextCompat.getColor(context, R.color.text_muted));
            
            // Round status badge
            holder.tvStatusBadge.setVisibility(View.VISIBLE);
            setupStatusBadge(holder.tvStatusBadge, item.jobCall);

            // Tags
            String tags = item.jobCall.getTags();
            if (tags != null && !tags.trim().isEmpty()) {
                holder.tvTags.setVisibility(View.VISIBLE);
                holder.tvTags.setText("Tags: " + tags);
            } else {
                holder.tvTags.setVisibility(View.GONE);
            }

            // Notes preview snippet
            String notes = item.jobCall.getNotes();
            if (notes != null && !notes.trim().isEmpty()) {
                holder.tvNotesPreview.setVisibility(View.VISIBLE);
                holder.tvNotesPreview.setText("Notes: " + notes);
            } else {
                holder.tvNotesPreview.setVisibility(View.GONE);
            }
        } else {
            holder.tvTrackedStatus.setText("Not tracked · tap to log call");
            holder.tvTrackedStatus.setTextColor(ContextCompat.getColor(context, R.color.accent_indigo));
            holder.tvStatusBadge.setVisibility(View.GONE);
            holder.tvTags.setVisibility(View.GONE);
            holder.tvNotesPreview.setVisibility(View.GONE);
        }

        // Action click listeners
        holder.btnActionCall.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDialClick(item.number);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item);
            }
        });
    }

    private void setupCallTypeStyle(ViewHolder holder, int callType) {
        String typeLabel;
        int fgRes, bgRes;

        holder.imgCallTypeIcon.setImageResource(android.R.drawable.ic_menu_call);
        switch (callType) {
            case 2: // Outgoing
                typeLabel = "Outgoing";
                fgRes = R.color.call_outgoing;
                bgRes = R.color.call_outgoing_bg;
                break;
            case 3: // Missed
                typeLabel = "Missed";
                fgRes = R.color.call_missed;
                bgRes = R.color.call_missed_bg;
                break;
            case 1: // Incoming
            default:
                typeLabel = (callType == 1) ? "Incoming" : "Call";
                fgRes = R.color.call_incoming;
                bgRes = R.color.call_incoming_bg;
                break;
        }

        int fg = ContextCompat.getColor(context, fgRes);
        int bg = ContextCompat.getColor(context, bgRes);

        holder.cardCallTypeBg.setCardBackgroundColor(bg);
        holder.imgCallTypeIcon.setImageTintList(ColorStateList.valueOf(fg));
    }

    private void setupStatusBadge(TextView tv, JobCall call) {
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

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        float density = context.getResources().getDisplayMetrics().density;
        gd.setCornerRadius(10 * density);
        tv.setBackground(gd);
        tv.setTextColor(textColor);
    }

    @Override
    public int getItemCount() {
        return callList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvCallerTitle, tvPhoneNumber, tvCallDetails, tvStatusBadge, tvTags, tvNotesPreview, tvTrackedStatus;
        MaterialCardView cardCallTypeBg, btnActionCall;
        ImageView imgCallTypeIcon;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCallerTitle = itemView.findViewById(R.id.tv_caller_title);
            tvPhoneNumber = itemView.findViewById(R.id.tv_phone_number);
            tvCallDetails = itemView.findViewById(R.id.tv_call_details);
            tvStatusBadge = itemView.findViewById(R.id.tv_status_badge);
            tvTags = itemView.findViewById(R.id.tv_tags);
            tvNotesPreview = itemView.findViewById(R.id.tv_notes_preview);
            tvTrackedStatus = itemView.findViewById(R.id.tv_tracked_status);
            cardCallTypeBg = itemView.findViewById(R.id.card_call_type_bg);
            btnActionCall = itemView.findViewById(R.id.btn_action_call);
            imgCallTypeIcon = itemView.findViewById(R.id.img_call_type_icon);
        }
    }
}

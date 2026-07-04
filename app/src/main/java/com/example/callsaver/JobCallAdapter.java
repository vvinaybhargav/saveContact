package com.example.callsaver;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
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

        holder.tvCompanyName.setText(call.getCompanyName());
        holder.tvPhoneNumber.setText(call.getPhoneNumber());

        // Set tags
        String tags = call.getTags();
        if (tags == null || tags.trim().isEmpty()) {
            holder.tvTags.setVisibility(View.GONE);
        } else {
            holder.tvTags.setVisibility(View.VISIBLE);
            holder.tvTags.setText("Tags: " + tags);
        }

        // Format Date Time
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
        String formattedDate = sdf.format(new Date(call.getTimestamp()));
        holder.tvCallTime.setText(formattedDate);

        // Dynamic Avatar Color based on Company Name
        String company = call.getCompanyName();
        String initial = company.isEmpty() ? "?" : String.valueOf(company.charAt(0)).toUpperCase();
        holder.tvAvatarText.setText(initial);

        int[] avatarColors = {0xFF6366F1, 0xFF10B981, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFFF59E0B, 0xFF14B8A6};
        int colorIndex = Math.abs(company.hashCode()) % avatarColors.length;
        holder.cardAvatar.setCardBackgroundColor(avatarColors[colorIndex]);

        // Status Badge customization
        setupStatusBadge(holder.tvStatusBadge, call.getRoundStatus());

        // Card Click Action
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(call);
            }
        });
    }

    private void setupStatusBadge(TextView tv, String status) {
        tv.setText(status);
        
        int textColor;
        int bgColor;
        
        // Define colors based on round status string
        if (status == null) {
            status = "Screening";
        }
        
        switch (status) {
            case "Rejected":
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
            case "Screening":
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

    @Override
    public int getItemCount() {
        return callList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvCompanyName, tvPhoneNumber, tvTags, tvCallTime, tvAvatarText, tvStatusBadge;
        MaterialCardView cardAvatar;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCompanyName = itemView.findViewById(R.id.tv_company_name);
            tvPhoneNumber = itemView.findViewById(R.id.tv_phone_number);
            tvTags = itemView.findViewById(R.id.tv_tags);
            tvCallTime = itemView.findViewById(R.id.tv_call_time);
            tvAvatarText = itemView.findViewById(R.id.tv_avatar_text);
            tvStatusBadge = itemView.findViewById(R.id.tv_status_badge);
            cardAvatar = itemView.findViewById(R.id.card_avatar);
        }
    }
}

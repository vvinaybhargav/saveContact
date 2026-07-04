package com.example.callsaver;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecentsAdapter extends RecyclerView.Adapter<RecentsAdapter.ViewHolder> {

    private final List<RecentsFragment.RecentCallModel> callLogsList;
    private final Context context;
    private final OnCallActionListener listener;

    public interface OnCallActionListener {
        void onDialClick(String phoneNumber);
        void onTrackClick(String phoneNumber);
    }

    public RecentsAdapter(Context context, List<RecentsFragment.RecentCallModel> callLogsList, OnCallActionListener listener) {
        this.context = context;
        this.callLogsList = callLogsList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_recent_call, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RecentsFragment.RecentCallModel call = callLogsList.get(position);

        // Name is present if the contact is saved in device
        String displayName = (call.name != null && !call.name.trim().isEmpty()) ? call.name : call.number;
        holder.tvCallerTitle.setText(displayName);

        // Format Date
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
        String dateStr = sdf.format(new Date(call.date));
        holder.tvCallTime.setText(dateStr);

        // Style based on Call Type
        setupCallTypeStyle(holder, call.type);

        // Bind action buttons
        holder.btnActionDial.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDialClick(call.number);
            }
        });

        holder.btnActionTrack.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTrackClick(call.number);
            }
        });
    }

    private void setupCallTypeStyle(ViewHolder holder, int callType) {
        int iconRes = android.R.drawable.ic_menu_call; // Default
        String typeLabel = "Call";
        int badgeColor = 0xFF3B82F6; // Blue default
        int badgeBgColor = 0xFFDBEAFE;

        switch (callType) {
            case 1: // CallLog.Calls.INCOMING_TYPE
                typeLabel = "Incoming";
                badgeColor = 0xFF10B981; // Green
                badgeBgColor = 0xFFD1FAE5;
                break;
            case 2: // CallLog.Calls.OUTGOING_TYPE
                typeLabel = "Outgoing";
                badgeColor = 0xFF6366F1; // Indigo
                badgeBgColor = 0xFFEDE9FE;
                break;
            case 3: // CallLog.Calls.MISSED_TYPE
                typeLabel = "Missed";
                badgeColor = 0xFFEF4444; // Red
                badgeBgColor = 0xFFFEE2E2;
                break;
        }

        holder.tvCallTypeLabel.setText(typeLabel);
        holder.tvCallTypeLabel.setTextColor(badgeColor);
        holder.cardCallTypeBg.setCardBackgroundColor(badgeBgColor);
        holder.imgCallTypeIcon.setImageTintList(ColorStateList.valueOf(badgeColor));
    }

    @Override
    public int getItemCount() {
        return callLogsList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvCallerTitle, tvCallTypeLabel, tvCallTime;
        MaterialCardView cardCallTypeBg, btnActionDial, btnActionTrack;
        ImageView imgCallTypeIcon;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCallerTitle = itemView.findViewById(R.id.tv_caller_title);
            tvCallTypeLabel = itemView.findViewById(R.id.tv_call_type_label);
            tvCallTime = itemView.findViewById(R.id.tv_call_time);
            cardCallTypeBg = itemView.findViewById(R.id.card_call_type_bg);
            btnActionDial = itemView.findViewById(R.id.btn_action_dial);
            btnActionTrack = itemView.findViewById(R.id.btn_action_track);
            imgCallTypeIcon = itemView.findViewById(R.id.img_call_type_icon);
        }
    }
}

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
import androidx.core.content.ContextCompat;
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
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault());
        String dateStr = sdf.format(new Date(call.date));
        holder.tvCallTime.setText(dateStr);

        // Style based on Call Type
        setupCallTypeStyle(holder, call.type);

        // Append which SIM the call used (dual-SIM only)
        if (call.sim != null && !call.sim.isEmpty()) {
            holder.tvCallTypeLabel.setText(holder.tvCallTypeLabel.getText() + " · " + call.sim);
        }

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
        // Indigo brand family (adapts to dark mode):
        //   Incoming -> indigo, Outgoing -> violet, Missed -> rose.
        String typeLabel;
        int fgRes, bgRes;

        switch (callType) {
            case 2: // CallLog.Calls.OUTGOING_TYPE
                typeLabel = "Outgoing";
                fgRes = R.color.call_outgoing;
                bgRes = R.color.call_outgoing_bg;
                break;
            case 3: // CallLog.Calls.MISSED_TYPE
                typeLabel = "Missed";
                fgRes = R.color.call_missed;
                bgRes = R.color.call_missed_bg;
                break;
            case 1: // CallLog.Calls.INCOMING_TYPE
            default:
                typeLabel = (callType == 1) ? "Incoming" : "Call";
                fgRes = R.color.call_incoming;
                bgRes = R.color.call_incoming_bg;
                break;
        }

        int fg = ContextCompat.getColor(context, fgRes);
        int bg = ContextCompat.getColor(context, bgRes);

        holder.tvCallTypeLabel.setText(typeLabel);
        holder.tvCallTypeLabel.setTextColor(fg);
        holder.cardCallTypeBg.setCardBackgroundColor(bg);
        holder.imgCallTypeIcon.setImageTintList(ColorStateList.valueOf(fg));
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

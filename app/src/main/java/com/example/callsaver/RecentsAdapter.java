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
        void onMessageClick(String phoneNumber);
        void onCopyClick(String phoneNumber);
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

        // Determine names and company names
        String contactName = (call.name != null && !call.name.trim().isEmpty()) ? call.name.trim() : null;
        String mappedRecruiter = (call.recruiterName != null && !call.recruiterName.trim().isEmpty()) ? call.recruiterName.trim() : null;
        String company = (call.companyName != null && !call.companyName.trim().isEmpty()) ? call.companyName.trim() : null;

        // Choose the primary name to show (contactName has priority, then recruiterName)
        String primaryName = contactName != null ? contactName : mappedRecruiter;

        // Build Title text (Name + Company)
        String titleText;
        if (primaryName != null && company != null) {
            titleText = primaryName + " (" + company + ")";
        } else if (primaryName != null) {
            titleText = primaryName;
        } else if (company != null) {
            titleText = company;
        } else {
            titleText = call.number; // default to showing phone number in title if no name/company
        }
        holder.tvCallerTitle.setText(titleText);

        // Display Call Time / Phone Number
        if (call.type == 100) {
            // For contacts search result
            holder.tvCallTime.setText(call.number);
        } else {
            // For call history items, format: [Phone Number] · [Formatted Date]
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault());
            String dateStr = sdf.format(new Date(call.date));
            if (call.number != null && !call.number.trim().isEmpty() && !call.number.equals(titleText)) {
                holder.tvCallTime.setText(call.number + "  ·  " + dateStr);
            } else {
                holder.tvCallTime.setText(dateStr);
            }
        }

        // Style based on Call Type
        setupCallTypeStyle(holder, call.type);

        // Append which SIM the call used (dual-SIM only, not applicable to generic contacts)
        if (call.type != 100 && call.sim != null && !call.sim.isEmpty()) {
            holder.tvCallTypeLabel.setText(holder.tvCallTypeLabel.getText() + " · " + call.sim);
        }

        // Bind action buttons
        holder.btnActionDial.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDialClick(call.number);
            }
        });

        // Tapping the row itself opens the log (same as the old dedicated "+" button).
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTrackClick(call.number);
            }
        });

        // Message/Copy are secondary actions - tucked into a popup menu off the small
        // "more" button instead of being separate hard-to-tap icons.
        holder.btnActionMore.setOnClickListener(v -> {
            androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(context, v);
            popup.getMenu().add("Message");
            popup.getMenu().add("Copy number");
            popup.getMenu().add("Log this call");
            popup.setOnMenuItemClickListener(item -> {
                if (listener == null) return true;
                String title = item.getTitle().toString();
                if ("Message".equals(title)) {
                    listener.onMessageClick(call.number);
                } else if ("Copy number".equals(title)) {
                    listener.onCopyClick(call.number);
                } else {
                    listener.onTrackClick(call.number);
                }
                return true;
            });
            popup.show();
        });
    }

    private void setupCallTypeStyle(ViewHolder holder, int callType) {
        // Indigo brand family (adapts to dark mode):
        //   Incoming -> indigo, Outgoing -> violet, Missed -> rose.
        String typeLabel;
        int fgRes, bgRes;

        if (callType == 100) {
            typeLabel = "Contact";
            fgRes = R.color.accent_indigo;
            bgRes = R.color.call_incoming_bg;
            holder.imgCallTypeIcon.setImageResource(android.R.drawable.ic_menu_myplaces);
        } else {
            holder.imgCallTypeIcon.setImageResource(android.R.drawable.ic_menu_call);
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
                case 5: // CallLog.Calls.REJECTED_TYPE
                    typeLabel = "Rejected";
                    fgRes = R.color.call_missed;
                    bgRes = R.color.call_missed_bg;
                    break;
                case 6: // CallLog.Calls.BLOCKED_TYPE
                    typeLabel = "Blocked";
                    fgRes = R.color.call_missed;
                    bgRes = R.color.call_missed_bg;
                    break;
                case 4: // CallLog.Calls.VOICEMAIL_TYPE
                    typeLabel = "Voicemail";
                    fgRes = R.color.call_outgoing;
                    bgRes = R.color.call_outgoing_bg;
                    break;
                case 1: // CallLog.Calls.INCOMING_TYPE
                default:
                    typeLabel = (callType == 1) ? "Incoming" : "Call";
                    fgRes = R.color.call_incoming;
                    bgRes = R.color.call_incoming_bg;
                    break;
            }
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
        MaterialCardView cardCallTypeBg, btnActionDial, btnActionMore;
        ImageView imgCallTypeIcon;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCallerTitle = itemView.findViewById(R.id.tv_caller_title);
            tvCallTypeLabel = itemView.findViewById(R.id.tv_call_type_label);
            tvCallTime = itemView.findViewById(R.id.tv_call_time);
            cardCallTypeBg = itemView.findViewById(R.id.card_call_type_bg);
            btnActionDial = itemView.findViewById(R.id.btn_action_dial);
            btnActionMore = itemView.findViewById(R.id.btn_action_more);
            imgCallTypeIcon = itemView.findViewById(R.id.img_call_type_icon);
        }
    }
}

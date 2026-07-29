package com.example.callsaver;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MailInboxAdapter extends RecyclerView.Adapter<MailInboxAdapter.MailViewHolder> {

    public interface OnMailClickListener {
        void onMailClick(EmailMessage email);
        void onAssignToLogClick(EmailMessage email);
    }

    private final Context context;
    private final List<EmailMessage> emailList = new ArrayList<>();
    private final OnMailClickListener listener;

    public MailInboxAdapter(Context context, OnMailClickListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setEmails(List<EmailMessage> emails) {
        this.emailList.clear();
        if (emails != null) {
            this.emailList.addAll(emails);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public MailViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_mail_inbox, parent, false);
        return new MailViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MailViewHolder holder, int position) {
        EmailMessage email = emailList.get(position);
        holder.bind(email);
    }

    @Override
    public int getItemCount() {
        return emailList.size();
    }

    class MailViewHolder extends RecyclerView.ViewHolder {

        private final TextView tvSenderAvatar;
        private final TextView tvSender;
        private final TextView tvTime;
        private final TextView tvSubject;
        private final TextView tvSnippet;
        private final ImageView btnOptions;

        public MailViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSenderAvatar = itemView.findViewById(R.id.tv_sender_avatar);
            tvSender = itemView.findViewById(R.id.tv_mail_sender);
            tvTime = itemView.findViewById(R.id.tv_mail_time);
            tvSubject = itemView.findViewById(R.id.tv_mail_subject);
            tvSnippet = itemView.findViewById(R.id.tv_mail_snippet);
            btnOptions = itemView.findViewById(R.id.btn_mail_options);
        }

        public void bind(EmailMessage email) {
            String name = email.getSenderDisplayName();
            tvSender.setText(name);

            String initial = !name.isEmpty() ? String.valueOf(name.charAt(0)).toUpperCase() : "M";
            tvSenderAvatar.setText(initial);

            tvSubject.setText(email.getSubject());
            tvSnippet.setText(email.getSnippet());

            long ts = email.getReceivedTimestamp();
            if (ts > 0) {
                String formatted = DateFormat.format("MMM dd, h:mm a", new Date(ts)).toString();
                tvTime.setText(formatted);
            } else {
                tvTime.setText("");
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onMailClick(email);
            });

            btnOptions.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(context, btnOptions);
                popup.getMenu().add(0, 1, 0, "📌 Assign Mail to Log");
                popup.getMenu().add(0, 2, 1, "👁️ View Email Details");

                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        if (listener != null) listener.onAssignToLogClick(email);
                        return true;
                    } else if (item.getItemId() == 2) {
                        if (listener != null) listener.onMailClick(email);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
        }
    }
}

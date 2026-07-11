package com.example.callsaver;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class UpcomingInterviewsAdapter extends RecyclerView.Adapter<UpcomingInterviewsAdapter.ViewHolder> {

    private final Context context;
    private final List<JobCall> interviewList;
    private final OnInterviewClickListener listener;

    public interface OnInterviewClickListener {
        void onInterviewClick(JobCall call);
    }

    public UpcomingInterviewsAdapter(Context context, List<JobCall> interviewList, OnInterviewClickListener listener) {
        this.context = context;
        this.interviewList = interviewList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_upcoming_interview, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        JobCall call = interviewList.get(position);
        
        holder.tvCompany.setText(call.getCompanyName() != null && !call.getCompanyName().isEmpty() ? call.getCompanyName() : "Unknown Company");
        holder.tvRole.setText(call.getAppliedRole() != null && !call.getAppliedRole().isEmpty() ? call.getAppliedRole() : "Job Position");
        holder.tvRound.setText(call.getRoundStatus() != null && !call.getRoundStatus().isEmpty() ? call.getRoundStatus() : "Screening");

        String schedule = call.getTentativeSchedule();
        if (schedule != null && !schedule.trim().isEmpty()) {
            holder.tvSchedule.setText(schedule.trim());
        } else {
            holder.tvSchedule.setText("Tentative / Yet to schedule");
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onInterviewClick(call);
            }
        });
    }

    @Override
    public int getItemCount() {
        return interviewList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvCompany;
        TextView tvRole;
        TextView tvRound;
        TextView tvSchedule;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCompany = itemView.findViewById(R.id.tv_item_ui_company);
            tvRole = itemView.findViewById(R.id.tv_item_ui_role);
            tvRound = itemView.findViewById(R.id.tv_item_ui_round);
            tvSchedule = itemView.findViewById(R.id.tv_item_ui_schedule);
        }
    }
}

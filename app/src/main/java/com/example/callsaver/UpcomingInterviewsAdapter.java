package com.example.callsaver;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class UpcomingInterviewsAdapter extends RecyclerView.Adapter<UpcomingInterviewsAdapter.ViewHolder> {

    private final Context context;
    private final List<JobCall> interviewList;
    private final OnInterviewClickListener listener;
    private final DatabaseHelper dbHelper;
    private final SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

    public interface OnInterviewClickListener {
        void onInterviewClick(JobCall call);
    }

    public UpcomingInterviewsAdapter(Context context, List<JobCall> interviewList, OnInterviewClickListener listener) {
        this.context = context;
        this.interviewList = interviewList;
        this.listener = listener;
        this.dbHelper = new DatabaseHelper(context);
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

        long[] times = dbHelper.getFirstAndRecentCallTimes(call.getId());
        String firstCallText = times[0] > 0 ? sdf.format(new Date(times[0])) : "-";
        String recentCallText = times[1] > 0 ? sdf.format(new Date(times[1])) : "NA";
        holder.tvFirstCall.setText("First call - " + firstCallText);
        holder.tvRecentCall.setText("Recent call - " + recentCallText);

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
        TextView tvFirstCall;
        TextView tvRecentCall;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCompany = itemView.findViewById(R.id.tv_item_ui_company);
            tvRole = itemView.findViewById(R.id.tv_item_ui_role);
            tvRound = itemView.findViewById(R.id.tv_item_ui_round);
            tvSchedule = itemView.findViewById(R.id.tv_item_ui_schedule);
            tvFirstCall = itemView.findViewById(R.id.tv_item_ui_first_call);
            tvRecentCall = itemView.findViewById(R.id.tv_item_ui_recent_call);
        }
    }
}

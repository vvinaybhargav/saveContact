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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class UpcomingInterviewsAdapter extends RecyclerView.Adapter<UpcomingInterviewsAdapter.ViewHolder> {

    private final Context context;
    private final List<JobCall> interviewList;
    private final OnInterviewClickListener listener;
    private final DatabaseHelper dbHelper;
    private final SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

    private Map<String, Integer> companyGroupSizes = new HashMap<>();
    private Map<String, List<JobCall>> companyGroupMembers = new HashMap<>();
    private Set<String> expandedGroupCompanies = new HashSet<>();
    private List<Boolean> headerFlags = new java.util.ArrayList<>();

    public interface OnInterviewClickListener {
        void onInterviewClick(JobCall call);
        void onFollowUpClick(JobCall call);
    }

    /** Optional: implement on the OnInterviewClickListener to rebuild the grouped list after a toggle. */
    public interface OnGroupToggleListener {
        void onGroupToggled();
    }

    public UpcomingInterviewsAdapter(Context context, List<JobCall> interviewList, OnInterviewClickListener listener) {
        this.context = context;
        this.interviewList = interviewList;
        this.listener = listener;
        this.dbHelper = new DatabaseHelper(context);
    }

    /** Called by UpcomingFragment after each list rebuild to update company-group state. */
    public void setCompanyGroups(Map<String, Integer> groupSizes, Map<String, List<JobCall>> groupMembers,
                                  Set<String> expandedCompanies, List<Boolean> headerFlags) {
        this.companyGroupSizes = groupSizes;
        this.companyGroupMembers = groupMembers;
        this.expandedGroupCompanies = expandedCompanies;
        this.headerFlags = headerFlags;
    }

    private String normalizeCompanyKey(String companyName) {
        return companyName == null ? "" : companyName.trim().toLowerCase(Locale.getDefault());
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
        String groupKey = normalizeCompanyKey(call.getCompanyName());
        boolean isGroupHeaderRow = position < headerFlags.size() && Boolean.TRUE.equals(headerFlags.get(position));

        if (isGroupHeaderRow) {
            bindHeaderRow(holder, call, groupKey);
            return;
        }

        // Undo bindHeaderRow's hiding, in case this ViewHolder is a recycled header row.
        holder.tvRole.setVisibility(View.VISIBLE);
        if (holder.divider != null) holder.divider.setVisibility(View.VISIBLE);
        if (holder.rowSchedule != null) holder.rowSchedule.setVisibility(View.VISIBLE);
        if (holder.rowCallTimes != null) holder.rowCallTimes.setVisibility(View.VISIBLE);

        // No placeholder like "Unknown Company" - fall back to whatever else is filled
        // in (recruiter name, then phone number), same as everywhere else in the app.
        String company = call.getCompanyName();
        String recruiterName = call.getRecruiterName();
        String companyDisplay;
        if (company != null && !company.trim().isEmpty()) {
            companyDisplay = company.trim();
        } else if (recruiterName != null && !recruiterName.trim().isEmpty()) {
            companyDisplay = recruiterName.trim();
        } else {
            companyDisplay = call.getPhoneNumber();
        }
        holder.tvCompany.setText(companyDisplay);
        holder.tvRole.setText(call.getAppliedRole() != null && !call.getAppliedRole().isEmpty() ? call.getAppliedRole() : "Job Position");
        String roundText = call.getRoundStatus() != null && !call.getRoundStatus().isEmpty() ? call.getRoundStatus() : "First time";
        if (call.getInterestRating() != null && !call.getInterestRating().isEmpty()) {
            roundText += " (" + call.getInterestRating() + ")";
        }
        holder.tvRound.setText(roundText);

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

        if (FollowUpUtils.needsFollowUp(call)) {
            holder.tvFollowUp.setVisibility(View.VISIBLE);
            holder.tvFollowUp.setText("⚠ Interview complete - yet to get an update");
            holder.tvFollowUp.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onFollowUpClick(call);
                }
            });
        } else {
            holder.tvFollowUp.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onInterviewClick(call);
            }
        });
    }

    /** Minimal collapsed/expanded company row: just the company name, lead count, and a chevron - tap toggles. */
    private void bindHeaderRow(ViewHolder holder, JobCall call, String groupKey) {
        int size = companyGroupSizes.containsKey(groupKey) ? companyGroupSizes.get(groupKey) : 1;
        boolean expanded = expandedGroupCompanies.contains(groupKey);
        String company = call.getCompanyName();
        String displayCompany = (company == null || company.trim().isEmpty()) ? call.getPhoneNumber() : company.trim();
        holder.tvCompany.setText(displayCompany);
        holder.tvRound.setText(size + (size == 1 ? " lead" : " leads") + " " + (expanded ? "▾" : "▸"));

        holder.tvRole.setVisibility(View.GONE);
        if (holder.divider != null) holder.divider.setVisibility(View.GONE);
        if (holder.rowSchedule != null) holder.rowSchedule.setVisibility(View.GONE);
        if (holder.rowCallTimes != null) holder.rowCallTimes.setVisibility(View.GONE);
        holder.tvFollowUp.setVisibility(View.GONE);

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
        TextView tvFollowUp;
        View divider;
        View rowSchedule;
        View rowCallTimes;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCompany = itemView.findViewById(R.id.tv_item_ui_company);
            tvRole = itemView.findViewById(R.id.tv_item_ui_role);
            tvRound = itemView.findViewById(R.id.tv_item_ui_round);
            tvSchedule = itemView.findViewById(R.id.tv_item_ui_schedule);
            tvFirstCall = itemView.findViewById(R.id.tv_item_ui_first_call);
            tvRecentCall = itemView.findViewById(R.id.tv_item_ui_recent_call);
            tvFollowUp = itemView.findViewById(R.id.tv_item_ui_followup);
            divider = itemView.findViewById(R.id.view_item_ui_divider);
            rowSchedule = itemView.findViewById(R.id.row_item_ui_schedule);
            rowCallTimes = itemView.findViewById(R.id.row_item_ui_call_times);
        }
    }
}

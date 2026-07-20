package com.example.callsaver;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only dashboard that turns the logged job calls into a pipeline funnel,
 * conversion rates, weekly call volume, and the most common role tags.
 */
public class AnalyticsActivity extends AppCompatActivity {

    private static final long WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final int WEEKS_SHOWN = 6;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analytics);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        DatabaseHelper dbHelper = new DatabaseHelper(this);
        List<JobCall> calls = dbHelper.getAllJobCalls();

        TextView tvEmpty = findViewById(R.id.tv_empty);
        View content = findViewById(R.id.layout_content);

        if (calls.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            content.setVisibility(View.GONE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);

        bindConversion(calls);
        bindFunnel(calls);
        bindWeekly(calls);
        bindTags(calls);
    }

    /**
     * Progression rank of a stage. Rejected is a terminal negative (rank 0 but still a lead).
     */
    private int stageRank(String status) {
        if (status == null) return 1;
        switch (status) {
            case "First time":
            case "Screening": return 1;
            case "Interested": return 2;
            case "1st Round": return 3;
            case "2nd Round": return 4;
            case "Final Round": return 5;
            case "HR / Salary": return 6;
            case "Offered": return 7;
            case "Not Interested":
            case "Negative": return 0;
            default: return 1;
        }
    }

    private int pct(int n, int total) {
        if (total <= 0) return 0;
        return (int) Math.round(100.0 * n / total);
    }

    private void bindConversion(List<JobCall> calls) {
        int total = calls.size();
        int reachedInterview = 0;
        int offered = 0;
        int rejected = 0;
        for (JobCall c : calls) {
            int rank = stageRank(c.getRoundStatus());
            if (rank >= 3) reachedInterview++; // rank 3 is 1st Round
            if ("Offered".equals(c.getRoundStatus())) offered++;
            if ("Negative".equals(c.getRoundStatus()) || "Not Interested".equals(c.getRoundStatus())) rejected++;
        }
        ((TextView) findViewById(R.id.tv_interview_rate)).setText(pct(reachedInterview, total) + "%");
        ((TextView) findViewById(R.id.tv_offer_rate)).setText(pct(offered, total) + "%");
        ((TextView) findViewById(R.id.tv_reject_rate)).setText(pct(rejected, total) + "%");
    }

    private void bindFunnel(List<JobCall> calls) {
        LinearLayout container = findViewById(R.id.container_funnel);
        LayoutInflater inflater = getLayoutInflater();

        int total = calls.size();
        int reachedInterview = 0;
        int offered = 0;
        for (JobCall c : calls) {
            int rank = stageRank(c.getRoundStatus());
            if (rank >= 3) reachedInterview++; // rank 3 is 1st Round
            if ("Offered".equals(c.getRoundStatus())) offered++;
        }

        addBar(container, inflater, "Leads", total + " (100%)", 100);
        addBar(container, inflater, "Reached interview",
                reachedInterview + " (" + pct(reachedInterview, total) + "%)", pct(reachedInterview, total));
        addBar(container, inflater, "Offers",
                offered + " (" + pct(offered, total) + "%)", pct(offered, total));
    }

    private void bindWeekly(List<JobCall> calls) {
        LinearLayout container = findViewById(R.id.container_weekly);
        LayoutInflater inflater = getLayoutInflater();

        int[] counts = new int[WEEKS_SHOWN];
        long now = System.currentTimeMillis();
        for (JobCall c : calls) {
            long diff = now - c.getTimestamp();
            if (diff < 0) diff = 0;
            int weekIndex = (int) (diff / WEEK_MILLIS);
            if (weekIndex >= 0 && weekIndex < WEEKS_SHOWN) {
                counts[weekIndex]++;
            }
        }

        int max = 1;
        for (int cnt : counts) max = Math.max(max, cnt);

        for (int i = 0; i < WEEKS_SHOWN; i++) {
            addBar(container, inflater, weekLabel(i), String.valueOf(counts[i]),
                    (int) Math.round(100.0 * counts[i] / max));
        }
    }

    private String weekLabel(int index) {
        if (index == 0) return "This week";
        if (index == 1) return "Last week";
        return index + " weeks ago";
    }

    private void bindTags(List<JobCall> calls) {
        LinearLayout container = findViewById(R.id.container_tags);
        LayoutInflater inflater = getLayoutInflater();

        // Count tags case-insensitively while keeping the first-seen display form.
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        for (JobCall c : calls) {
            String tags = c.getTags();
            if (tags == null || tags.trim().isEmpty()) continue;
            for (String raw : tags.split(",")) {
                String tag = raw.trim();
                if (tag.isEmpty()) continue;
                String key = tag.toLowerCase();
                counts.put(key, (counts.containsKey(key) ? counts.get(key) : 0) + 1);
                if (!display.containsKey(key)) display.put(key, tag);
            }
        }

        if (counts.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("No tags added yet.");
            tv.setTextColor(getResources().getColor(R.color.text_secondary));
            tv.setTextSize(13);
            container.addView(tv);
            return;
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });

        int max = entries.get(0).getValue();
        int limit = Math.min(5, entries.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> e = entries.get(i);
            addBar(container, inflater, display.get(e.getKey()), String.valueOf(e.getValue()),
                    (int) Math.round(100.0 * e.getValue() / max));
        }
    }

    private void addBar(LinearLayout container, LayoutInflater inflater, String label, String value, int percent) {
        View row = inflater.inflate(R.layout.item_analytics_bar, container, false);
        ((TextView) row.findViewById(R.id.tv_bar_label)).setText(label);
        ((TextView) row.findViewById(R.id.tv_bar_value)).setText(value);
        ProgressBar pb = row.findViewById(R.id.bar_progress);
        pb.setProgress(Math.max(0, Math.min(100, percent)));
        container.addView(row);
    }
}

package com.example.callsaver;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class RecentsFragment extends Fragment implements RecentsAdapter.OnCallActionListener {

    private RecyclerView rvRecents;
    private View emptyStateLayout;
    
    private List<RecentCallModel> callLogsList;
    private RecentsAdapter adapter;

    public static class RecentCallModel {
        public String number;
        public String name;
        public int type;
        public long date;

        public RecentCallModel(String number, String name, int type, long date) {
            this.number = number;
            this.name = name;
            this.type = type;
            this.date = date;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recents, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvRecents = view.findViewById(R.id.rv_recents);
        emptyStateLayout = view.findViewById(R.id.empty_state_layout);

        rvRecents.setLayoutManager(new LinearLayoutManager(requireContext()));
        callLogsList = new ArrayList<>();
        adapter = new RecentsAdapter(requireContext(), callLogsList, this);
        rvRecents.setAdapter(adapter);

        loadCallLogs();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadCallLogs();
    }

    /**
     * Reads the call logs from the Android System and binds them to the adapter.
     */
    private void loadCallLogs() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            showEmptyState();
            return;
        }

        callLogsList.clear();

        String[] projection = new String[]{
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE
        };

        try (Cursor cursor = requireContext().getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC LIMIT 50" // Query newest 50 calls for performance
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER);
                int nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME);
                int typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE);
                int dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE);

                do {
                    String number = cursor.getString(numberIdx);
                    String name = cursor.getString(nameIdx);
                    int type = cursor.getInt(typeIdx);
                    long date = cursor.getLong(dateIdx);

                    callLogsList.add(new RecentCallModel(number, name, type, date));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (callLogsList.isEmpty()) {
            showEmptyState();
        } else {
            emptyStateLayout.setVisibility(View.GONE);
            rvRecents.setVisibility(View.VISIBLE);
            adapter.notifyDataSetChanged();
        }
    }

    private void showEmptyState() {
        emptyStateLayout.setVisibility(View.VISIBLE);
        rvRecents.setVisibility(View.GONE);
    }

    /**
     * Triggers the system phone app dialer. Dual SIM selection is automatically handled
     * by the system dialer app when the user initiates the call.
     */
    @Override
    public void onDialClick(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL);
            intent.setData(Uri.parse("tel:" + phoneNumber));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Directs the user to the Tracker tab and opens the log dialog pre-filled with the number.
     */
    @Override
    public void onTrackClick(String phoneNumber) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openTrackerWithNumber(phoneNumber);
        }
    }
}

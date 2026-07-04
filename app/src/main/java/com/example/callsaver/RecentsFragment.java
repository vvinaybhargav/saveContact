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
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

public class RecentsFragment extends Fragment implements RecentsAdapter.OnCallActionListener {

    private RecyclerView rvRecents;
    private View emptyStateLayout;
    private FloatingActionButton fabShowDialer;
    private MaterialCardView cardDialerDrawer;
    private TextView tvDialerDigits;
    
    private List<RecentCallModel> callLogsList;
    private RecentsAdapter adapter;
    private StringBuilder dialedDigits = new StringBuilder();

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
        fabShowDialer = view.findViewById(R.id.fab_show_dialer);
        cardDialerDrawer = view.findViewById(R.id.card_dialer_drawer);
        tvDialerDigits = view.findViewById(R.id.tv_dialer_digits);

        rvRecents.setLayoutManager(new LinearLayoutManager(requireContext()));
        callLogsList = new ArrayList<>();
        adapter = new RecentsAdapter(requireContext(), callLogsList, this);
        rvRecents.setAdapter(adapter);

        // Dialer toggle click listener
        fabShowDialer.setOnClickListener(v -> toggleDialerVisibility(true));

        // Setup Keypad clicks
        setupKeypad(view);

        loadCallLogs();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadCallLogs();
    }

    /**
     * Binds click events to the dialer keypad keys.
     */
    private void setupKeypad(View view) {
        int[] keyIds = {
                R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3,
                R.id.key_4, R.id.key_5, R.id.key_6, R.id.key_7,
                R.id.key_8, R.id.key_9, R.id.key_star, R.id.key_hash
        };

        for (int id : keyIds) {
            TextView key = view.findViewById(id);
            if (key != null) {
                key.setOnClickListener(v -> {
                    dialedDigits.append(key.getText());
                    updateDialerDigitsDisplay();
                });
            }
        }

        ImageView btnBackspace = view.findViewById(R.id.btn_dialer_backspace);
        if (btnBackspace != null) {
            btnBackspace.setOnClickListener(v -> {
                if (dialedDigits.length() > 0) {
                    dialedDigits.deleteCharAt(dialedDigits.length() - 1);
                    updateDialerDigitsDisplay();
                }
            });
            btnBackspace.setOnLongClickListener(v -> {
                dialedDigits.setLength(0);
                updateDialerDigitsDisplay();
                return true;
            });
        }

        View btnClose = view.findViewById(R.id.btn_dialer_close);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> toggleDialerVisibility(false));
        }

        View btnCall = view.findViewById(R.id.btn_dialer_call);
        if (btnCall != null) {
            btnCall.setOnClickListener(v -> {
                String number = dialedDigits.toString().trim();
                if (!number.isEmpty()) {
                    onDialClick(number);
                } else {
                    Toast.makeText(requireContext(), "Please enter a number first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        View btnTrack = view.findViewById(R.id.btn_dialer_track);
        if (btnTrack != null) {
            btnTrack.setOnClickListener(v -> {
                String number = dialedDigits.toString().trim();
                if (!number.isEmpty()) {
                    onTrackClick(number);
                    // Clear dialer digits after routing
                    dialedDigits.setLength(0);
                    updateDialerDigitsDisplay();
                    toggleDialerVisibility(false);
                } else {
                    Toast.makeText(requireContext(), "Please enter a number first", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void updateDialerDigitsDisplay() {
        if (tvDialerDigits != null) {
            tvDialerDigits.setText(dialedDigits.toString());
        }
    }

    private void toggleDialerVisibility(boolean show) {
        if (cardDialerDrawer == null || fabShowDialer == null) return;
        if (show) {
            cardDialerDrawer.setVisibility(View.VISIBLE);
            fabShowDialer.setVisibility(View.GONE);
        } else {
            cardDialerDrawer.setVisibility(View.GONE);
            fabShowDialer.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Reads the call logs from the Android System and binds them to the adapter.
     * Compatibility fix: Limits loop counter in Java rather than appending LIMIT clause to sortOrder.
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

        // Query history cleanly sorted by date. Enforces compatible loop limiting in Java.
        try (Cursor cursor = requireContext().getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC"
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER);
                int nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME);
                int typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE);
                int dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE);

                int count = 0;
                do {
                    String number = cursor.getString(numberIdx);
                    String name = cursor.getString(nameIdx);
                    int type = cursor.getInt(typeIdx);
                    long date = cursor.getLong(dateIdx);

                    callLogsList.add(new RecentCallModel(number, name, type, date));
                    count++;
                } while (cursor.moveToNext() && count < 50); // Hard capped at 50 logs for display performance
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

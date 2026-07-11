package com.example.callsaver;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.MenuItem;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;
import android.content.SharedPreferences;
import androidx.appcompat.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int ALL_PERMISSIONS_REQUEST_CODE = 200;
    private static final int REQ_DEFAULT_DIALER = 300;
    private static final int REQ_CODE_OVERLAY = 400;
    private static final int REQ_CODE_SCREENING = 500;
    private static final int REQ_CODE_STORAGE_MANAGE = 600;

    private BottomNavigationView bottomNavigation;
    private RecentsFragment recentsFragment;
    private TrackerFragment trackerFragment;
    private CombinedFragment combinedFragment;

    private final String[] requiredPermissions = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.GET_ACCOUNTS
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Fragments
        recentsFragment = new RecentsFragment();
        trackerFragment = new TrackerFragment();
        combinedFragment = new CombinedFragment();

        bottomNavigation = findViewById(R.id.bottom_navigation);

        // Set up tab switching
        bottomNavigation.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                Fragment selectedFragment = null;
                int itemId = item.getItemId();
                
                if (itemId == R.id.navigation_recents) {
                    selectedFragment = recentsFragment;
                } else if (itemId == R.id.navigation_tracker) {
                    selectedFragment = trackerFragment;
                } else if (itemId == R.id.navigation_combined) {
                    selectedFragment = combinedFragment;
                }

                if (selectedFragment != null) {
                    getSupportFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, selectedFragment)
                            .commit();
                    return true;
                }
                return false;
            }
        });

        // Load Recents fragment by default
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, recentsFragment)
                .commit();

        // Auto check/request permissions on first launch
        requestRequiredPermissionsIfMissing();

        // Check overlay window permission for caller ID banner
        checkOverlayPermission();

        ImageView btnSettings = findViewById(R.id.btn_main_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }
    }

    /**
     * Prompts the user to make this the default phone app if it isn't already.
     * Uses RoleManager on Android 10+ and TelecomManager on older versions.
     */
    private void offerDefaultDialer() {
        if (isAlreadyDefaultDialer()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);
                if (roleManager != null
                        && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)
                        && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                    startActivityForResult(intent, REQ_DEFAULT_DIALER);
                }
            } else {
                Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                                getPackageName());
                startActivity(intent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isAlreadyDefaultDialer() {
        TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        return tm != null && getPackageName().equals(tm.getDefaultDialerPackage());
    }

    /**
     * Toggles the Bottom Bar selection to Tracker and launches the manual log dialog pre-filled.
     */
    public void openTrackerWithNumber(String phoneNumber) {
        bottomNavigation.setSelectedItemId(R.id.navigation_tracker);
        
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, trackerFragment)
                .commit();

        // Slight delay to ensure the fragment is attached and views are inflated before showing dialog
        bottomNavigation.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (trackerFragment != null && trackerFragment.isAdded()) {
                    JobCall prefilledCall = new JobCall(phoneNumber, "", "Screening", "", "", 0, System.currentTimeMillis());
                    trackerFragment.showAddEditCallDialog(prefilledCall);
                }
            }
        }, 250);
    }

    private String[] getRequiredPermissions() {
        List<String> perms = new ArrayList<>(Arrays.asList(requiredPermissions));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return perms.toArray(new String[0]);
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Overlay Permission Required")
                        .setMessage("This app displays a floating Caller ID banner (like Truecaller) on recruiter calls. Tap 'OK' to enable this in settings.")
                        .setPositiveButton("OK", (dialog, which) -> {
                            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQ_CODE_OVERLAY);
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> requestCallScreeningRole())
                        .show();
            } else {
                requestCallScreeningRole();
            }
        } else {
            requestCallScreeningRole();
        }
    }

    private void requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (roleManager != null
                    && roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                    Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING);
                    startActivityForResult(intent, REQ_CODE_SCREENING);
                } else {
                    checkStorageManagerPermission();
                }
            } else {
                checkStorageManagerPermission();
            }
        } else {
            checkStorageManagerPermission();
        }
    }

    private void checkStorageManagerPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("All Files Access Required")
                        .setMessage("On Android 11+, the app needs permission to access call recordings inside your system's folder. Tap 'OK' to enable this in settings.")
                        .setPositiveButton("OK", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:" + getPackageName()));
                                startActivityForResult(intent, REQ_CODE_STORAGE_MANAGE);
                            } catch (Exception e) {
                                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                                startActivityForResult(intent, REQ_CODE_STORAGE_MANAGE);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_OVERLAY) {
            requestCallScreeningRole();
        } else if (requestCode == REQ_CODE_SCREENING) {
            checkStorageManagerPermission();
        }
    }

    private void requestRequiredPermissionsIfMissing() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String perm : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(perm);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    listPermissionsNeeded.toArray(new String[0]),
                    ALL_PERMISSIONS_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ALL_PERMISSIONS_REQUEST_CODE) {
            // Forward result to fragments if they are attached
            if (recentsFragment != null && recentsFragment.isAdded()) {
                recentsFragment.onResume();
            }
            if (trackerFragment != null && trackerFragment.isAdded()) {
                trackerFragment.onResume();
            }
            if (combinedFragment != null && combinedFragment.isAdded()) {
                combinedFragment.onResume();
            }
        }
    }

    private void showSettingsDialog() {
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_settings, null);

        EditText etDeepgram = dialogView.findViewById(R.id.et_settings_deepgram_key);
        EditText etOpenAi = dialogView.findViewById(R.id.et_settings_openai_key);
        View btnCancel = dialogView.findViewById(R.id.btn_settings_cancel);
        View btnSave = dialogView.findViewById(R.id.btn_settings_save);

        SharedPreferences prefs = getSharedPreferences("CallSaverPrefs", MODE_PRIVATE);
        etDeepgram.setText(prefs.getString("deepgram_api_key", ""));
        etOpenAi.setText(prefs.getString("openai_api_key", ""));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        View tvDiagnostics = dialogView.findViewById(R.id.tv_settings_diagnostics);
        if (tvDiagnostics != null) {
            tvDiagnostics.setOnClickListener(v -> {
                dialog.dismiss();
                showDebugLogsDialog();
            });
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String dg = etDeepgram.getText().toString().trim();
            String oa = etOpenAi.getText().toString().trim();
            prefs.edit()
                    .putString("deepgram_api_key", dg)
                    .putString("openai_api_key", oa)
                    .apply();
            Toast.makeText(this, "API Keys saved successfully!", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();

        View parent = (View) dialogView.getParent();
        if (parent != null) {
            parent.setBackgroundResource(R.drawable.spinner_border);
        }
    }

    private void showDebugLogsDialog() {
        String logs = DebugLogger.readLogs(this);

        TextView tv = new TextView(this);
        tv.setText(logs);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        tv.setPadding(40, 24, 40, 24);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("Diagnostic logs")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear", (d, w) -> {
                    DebugLogger.clearLogs(this);
                    Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", logs));
                        Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}

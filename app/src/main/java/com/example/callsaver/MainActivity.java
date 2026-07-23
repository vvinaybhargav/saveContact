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

    private BottomNavigationView bottomNavigation;
    private RecentsFragment recentsFragment;
    private TrackerFragment trackerFragment;
    private UpcomingFragment upcomingFragment;

    private final String[] requiredPermissions = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.GET_ACCOUNTS
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Fragments
        recentsFragment = new RecentsFragment();
        trackerFragment = new TrackerFragment();
        upcomingFragment = new UpcomingFragment();

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
                } else if (itemId == R.id.navigation_upcoming) {
                    selectedFragment = upcomingFragment;
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

        // Load Recents / Dialer fragment by default
        bottomNavigation.setSelectedItemId(R.id.navigation_recents);

        // Some OEM skins (ColorOS/MIUI) evaluate Default Phone App eligibility based
        // on whether the app already holds phone-related runtime permissions at the
        // moment the role picker is built - on a fresh install those aren't granted
        // yet, so the app gets silently excluded from the OS's picker if we ask for
        // the role before permissions exist. Request permissions first; the dialer
        // prompt fires from onRequestPermissionsResult once they're granted (or
        // immediately below if everything was already granted from a prior run).
        requestRequiredPermissionsIfMissing();
        if (allRequiredPermissionsGranted()) {
            offerDefaultDialer();
        }

        // Check overlay window permission for caller ID banner
        checkOverlayPermission();

        // Android 14+ requires explicit special-app-access grant for full-screen
        // intents; without it, an incoming call on the lock screen silently
        // downgrades to sound-only (no screen launch) with no error shown anywhere.
        checkFullScreenIntentPermission();

        ImageView btnSettings = findViewById(R.id.btn_main_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        }

        handleOpenTabIntent(getIntent());

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            FollowUpNotifier.checkAndNotify(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenTabIntent(intent);
    }

    private void handleOpenTabIntent(Intent intent) {
        if (intent == null) return;
        
        // Handle incoming dial/tel intents directly
        if (Intent.ACTION_DIAL.equals(intent.getAction()) || Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null && "tel".equals(data.getScheme())) {
                String number = data.getSchemeSpecificPart();
                if (bottomNavigation != null) {
                    bottomNavigation.setSelectedItemId(R.id.navigation_recents);
                }
                if (recentsFragment != null) {
                    recentsFragment.setDialedNumber(number);
                }
                return;
            }
        }

        String openTab = intent.getStringExtra("open_tab");
        if (bottomNavigation == null) return;
        if ("upcoming".equals(openTab)) {
            bottomNavigation.setSelectedItemId(R.id.navigation_upcoming);
        } else if ("tracker".equals(openTab)) {
            bottomNavigation.setSelectedItemId(R.id.navigation_tracker);
        } else if ("recents".equals(openTab)) {
            bottomNavigation.setSelectedItemId(R.id.navigation_recents);
        }
    }

    /**
     * Prompts the user to make this the default phone app if it isn't already.
     * Uses RoleManager on Android 10+ and TelecomManager on older versions.
     */
    private void offerDefaultDialer() {
        // TelecomManager's getDefaultDialerPackage() is the authoritative source for
        // "are we actually the default dialer right now" - trust it over
        // RoleManager.isRoleHeld(), which can disagree (e.g. after repeated
        // install/uninstall during testing) and would otherwise silently skip the
        // prompt below even though we're genuinely not the default.
        if (isAlreadyDefaultDialer()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                    startActivityForResult(intent, REQ_DEFAULT_DIALER);
                } else {
                    Toast.makeText(this, "Default Phone App role isn't available on this device.", Toast.LENGTH_LONG).show();
                }
            } else {
                Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                                getPackageName());
                startActivity(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not prompt for Default Phone App: " + e.getMessage(), Toast.LENGTH_LONG).show();
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

        // Slight delay to ensure the fragment is attached before opening the capture screen.
        bottomNavigation.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Same capture screen used everywhere else (in-call, Tracker, Upcoming, "+").
                // Reuse the existing lead for this number if there is one, instead of
                // always creating a new one.
                JobCall existing = new DatabaseHelper(MainActivity.this).getJobCallByNumber(MainActivity.this, phoneNumber);
                Intent intent = new Intent(MainActivity.this, InCallActivity.class);
                intent.putExtra("mode", "review");
                intent.putExtra("phone_number", phoneNumber);
                if (existing != null) {
                    intent.putExtra("company_name", existing.getCompanyName());
                    intent.putExtra("round_status", existing.getRoundStatus());
                    intent.putExtra("tags", existing.getTags());
                    intent.putExtra("job_call_id", (long) existing.getId());
                    intent.putExtra("recruiter_name", existing.getRecruiterName());
                } else {
                    intent.putExtra("job_call_id", -1L);
                }
                startActivity(intent);
            }
        }, 250);
    }

    private String[] getRequiredPermissions() {
        List<String> perms = new ArrayList<>(Arrays.asList(requiredPermissions));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }

    private void checkOverlayPermission() {
        // "Display over other apps" is still used to launch the post-call popup from the
        // background. The call-screening role is no longer requested here - now that
        // CallSaver is the full default dialer, its InCallService sees every call
        // directly, so screening was a redundant extra permission prompt.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Overlay Permission Required")
                    .setMessage("This app displays a full-screen Caller ID layout on recruiter calls. Tap 'OK' to enable this in settings.")
                    .setPositiveButton("OK", (dialog, which) -> {
                        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQ_CODE_OVERLAY);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private void checkFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && !nm.canUseFullScreenIntent()) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Full-Screen Call Alerts")
                        .setMessage("To show the incoming-call screen when your phone is locked, allow CallSaver to use full-screen notifications. Without this, incoming calls will only ring with no screen shown.")
                        .setPositiveButton("OK", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(this, "Could not open full-screen intent settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
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

    private boolean allRequiredPermissionsGranted() {
        for (String perm : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
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
            if (upcomingFragment != null && upcomingFragment.isAdded()) {
                upcomingFragment.onResume();
            }
            // Now that phone-related permissions have been answered, this is the
            // right moment to prompt for Default Phone App - some OEM skins wouldn't
            // have listed us as eligible before these were granted.
            if (allRequiredPermissionsGranted()) {
                offerDefaultDialer();
            }
        }
    }
}

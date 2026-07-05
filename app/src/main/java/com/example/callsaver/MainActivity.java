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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int ALL_PERMISSIONS_REQUEST_CODE = 200;
    private static final int REQ_DEFAULT_DIALER = 300;

    private BottomNavigationView bottomNavigation;
    private RecentsFragment recentsFragment;
    private TrackerFragment trackerFragment;

    private final String[] requiredPermissions = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.CALL_PHONE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Fragments
        recentsFragment = new RecentsFragment();
        trackerFragment = new TrackerFragment();

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

        // Offer to become the default phone app (enables the full-screen call UI)
        offerDefaultDialer();
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

    private void requestRequiredPermissionsIfMissing() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String perm : requiredPermissions) {
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
        }
    }
}

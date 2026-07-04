package com.example.callsaver;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int OVERLAY_REQUEST_CODE = 101;

    private TextView badgePhone;
    private TextView badgeContacts;
    private TextView badgeOverlay;
    private Button btnGrantPerms;
    private Button btnGrantOverlay;

    private final String[] requiredPermissions = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        badgePhone = findViewById(R.id.badge_phone_permission);
        badgeContacts = findViewById(R.id.badge_contacts_permission);
        badgeOverlay = findViewById(R.id.badge_overlay_permission);
        btnGrantPerms = findViewById(R.id.btn_grant_perms);
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay);

        btnGrantPerms.setOnClickListener(v -> requestRuntimePermissions());
        btnGrantOverlay.setOnClickListener(v -> requestOverlayPermission());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private void updatePermissionStatus() {
        // Check Phone & Call Log permissions
        boolean hasPhone = hasPermission(Manifest.permission.READ_PHONE_STATE) &&
                           hasPermission(Manifest.permission.READ_CALL_LOG);
        setBadgeStyle(badgePhone, hasPhone);

        // Check Contacts permissions
        boolean hasContacts = hasPermission(Manifest.permission.READ_CONTACTS) &&
                              hasPermission(Manifest.permission.WRITE_CONTACTS);
        setBadgeStyle(badgeContacts, hasContacts);

        // Check Overlay permission
        boolean hasOverlay = checkOverlayPermission();
        setBadgeStyle(badgeOverlay, hasOverlay);

        // Enable or disable buttons depending on status
        if (hasPhone && hasContacts) {
            btnGrantPerms.setEnabled(false);
            btnGrantPerms.setText("Runtime Permissions Granted");
            btnGrantPerms.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.status_green));
        } else {
            btnGrantPerms.setEnabled(true);
            btnGrantPerms.setText(R.string.btn_grant_permissions);
            btnGrantPerms.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_indigo));
        }

        if (hasOverlay) {
            btnGrantOverlay.setEnabled(false);
            btnGrantOverlay.setText("Overlay Permission Enabled");
        } else {
            btnGrantOverlay.setEnabled(true);
            btnGrantOverlay.setText(R.string.btn_open_overlay_settings);
        }
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void setBadgeStyle(TextView textView, boolean isGranted) {
        textView.setText(isGranted ? R.string.granted : R.string.pending);
        int textColor = getResources().getColor(isGranted ? R.color.status_green : R.color.status_warning);
        int bgColor = getResources().getColor(isGranted ? R.color.status_green_bg : R.color.status_warning_bg);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        // Rounded corner radius in pixels (8dp converted to pixels approx 24px)
        float density = getResources().getDisplayMetrics().density;
        gd.setCornerRadius(8 * density);
        textView.setBackground(gd);
        textView.setTextColor(textColor);
    }

    private void requestRuntimePermissions() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String perm : requiredPermissions) {
            if (!hasPermission(perm)) {
                listPermissionsNeeded.add(perm);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    listPermissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        } else {
            Toast.makeText(this, "All runtime permissions are already granted.", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_REQUEST_CODE);
            } else {
                Toast.makeText(this, "Draw overlay permission already granted.", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Overlay permission not required on this Android version.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updatePermissionStatus();
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, "Permissions granted! App will monitor call events.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Some permissions were denied. The app may not work correctly.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_REQUEST_CODE) {
            updatePermissionStatus();
        }
    }
}

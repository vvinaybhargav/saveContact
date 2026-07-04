package com.example.callsaver;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.ContentProviderOperation;
import android.database.Cursor;
import android.provider.ContactsContract;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements JobCallAdapter.OnItemClickListener {

    private static final int ALL_PERMISSIONS_REQUEST_CODE = 200;
    
    private RecyclerView rvJobCalls;
    private View emptyStateLayout;
    private MaterialCardView cardPermissionsBanner;
    private FloatingActionButton fabAddCall;
    
    private DatabaseHelper dbHelper;
    private JobCallAdapter adapter;
    private List<JobCall> callList;

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

        dbHelper = new DatabaseHelper(this);

        rvJobCalls = findViewById(R.id.rv_job_calls);
        emptyStateLayout = findViewById(R.id.empty_state_layout);
        cardPermissionsBanner = findViewById(R.id.card_permissions_banner);
        fabAddCall = findViewById(R.id.fab_add_call);

        // Setup RecyclerView
        rvJobCalls.setLayoutManager(new LinearLayoutManager(this));
        callList = new ArrayList<>();
        adapter = new JobCallAdapter(this, callList, this);
        rvJobCalls.setAdapter(adapter);

        // Permissions banner click - redirects to check/request
        cardPermissionsBanner.setOnClickListener(v -> handlePermissionsRequestFlow());

        // Log call manually FAB click
        fabAddCall.setOnClickListener(v -> showAddEditCallDialog(null));

        // Load logs
        refreshDashboardList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissionsBannerVisibility();
        refreshDashboardList();
    }

    /**
     * Refreshes the calls displayed on the RecyclerView from the SQLite DB.
     */
    private void refreshDashboardList() {
        List<JobCall> updatedCalls = dbHelper.getAllJobCalls();
        callList.clear();
        callList.addAll(updatedCalls);
        adapter.notifyDataSetChanged();

        if (callList.isEmpty()) {
            emptyStateLayout.setVisibility(View.VISIBLE);
            rvJobCalls.setVisibility(View.GONE);
        } else {
            emptyStateLayout.setVisibility(View.GONE);
            rvJobCalls.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Checks if all required permissions are granted and updates the banner visibility.
     */
    private void checkPermissionsBannerVisibility() {
        boolean allGranted = true;
        for (String perm : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        // Check overlay permission
        boolean overlayGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            overlayGranted = Settings.canDrawOverlays(this);
        }

        if (allGranted && overlayGranted) {
            cardPermissionsBanner.setVisibility(View.GONE);
        } else {
            cardPermissionsBanner.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Triggers permission requests or opens overlay configuration settings.
     */
    private void handlePermissionsRequestFlow() {
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // Open overlay window settings directly if only overlay is missing
            Toast.makeText(this, "Please enable 'Display over other apps' to allow popup logs.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    /**
     * RecyclerView Item Click -> triggers edit/update of the call record.
     */
    @Override
    public void onItemClick(JobCall jobCall) {
        showAddEditCallDialog(jobCall);
    }

    /**
     * Opens the modal dialog to either Log a manual call (if call is null)
     * or edit/delete an existing logged call (if call is not null).
     */
    private void showAddEditCallDialog(final JobCall editCall) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_add_call, null);
        builder.setView(dialogView);

        TextView dialogTitle = dialogView.findViewById(R.id.dialog_title);
        EditText etPhone = dialogView.findViewById(R.id.et_phone);
        EditText etCompany = dialogView.findViewById(R.id.et_company);
        EditText etTags = dialogView.findViewById(R.id.et_tags);
        Spinner spinnerRound = dialogView.findViewById(R.id.spinner_round);
        
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_dialog_save);
        Button btnDelete = dialogView.findViewById(R.id.btn_dialog_delete);
        Button btnSaveContacts = dialogView.findViewById(R.id.btn_dialog_save_contacts);

        // Bind Spinner choices
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(this,
                R.array.round_statuses, android.R.layout.simple_spinner_item);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRound.setAdapter(spinnerAdapter);

        final AlertDialog alertDialog = builder.create();

        // Configure Dialog Mode (Edit vs. Add)
        if (editCall != null) {
            dialogTitle.setText(R.string.title_edit_job_call);
            etPhone.setText(editCall.getPhoneNumber());
            etCompany.setText(editCall.getCompanyName());
            etTags.setText(editCall.getTags());
            btnSave.setText(R.string.btn_update);
            btnDelete.setVisibility(View.VISIBLE);
            
            // Show/hide 'Save to Contacts' button based on existence
            if (isContactExists(editCall.getPhoneNumber())) {
                btnSaveContacts.setVisibility(View.GONE);
            } else {
                btnSaveContacts.setVisibility(View.VISIBLE);
            }

            // Set spinner selection
            if (editCall.getRoundStatus() != null) {
                int position = spinnerAdapter.getPosition(editCall.getRoundStatus());
                spinnerRound.setSelection(position >= 0 ? position : 0);
            }
        } else {
            dialogTitle.setText(R.string.title_add_job_call);
            btnSave.setText(R.string.btn_add);
            btnDelete.setVisibility(View.GONE);
            btnSaveContacts.setVisibility(View.GONE);
        }

        btnCancel.setOnClickListener(v -> alertDialog.dismiss());

        // Save directly to contacts click action
        btnSaveContacts.setOnClickListener(v -> {
            String company = etCompany.getText().toString().trim();
            if (company.isEmpty()) {
                Toast.makeText(MainActivity.this, R.string.msg_company_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (editCall != null && saveContactDirectly(company, editCall.getPhoneNumber())) {
                Toast.makeText(MainActivity.this, "Saved to phone contacts!", Toast.LENGTH_SHORT).show();
                btnSaveContacts.setVisibility(View.GONE);
            } else {
                Toast.makeText(MainActivity.this, "Failed to save contact.", Toast.LENGTH_SHORT).show();
            }
        });

        // Delete click action
        btnDelete.setOnClickListener(v -> {
            if (editCall != null) {
                dbHelper.deleteJobCall(editCall.getId());
                Toast.makeText(MainActivity.this, R.string.msg_deleted, Toast.LENGTH_SHORT).show();
                refreshDashboardList();
                alertDialog.dismiss();
            }
        });

        // Save / Update click action
        btnSave.setOnClickListener(v -> {
            String phone = etPhone.getText().toString().trim();
            String company = etCompany.getText().toString().trim();
            String tags = etTags.getText().toString().trim();
            String round = spinnerRound.getSelectedItem().toString();

            if (phone.isEmpty()) {
                Toast.makeText(MainActivity.this, R.string.msg_phone_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (company.isEmpty()) {
                Toast.makeText(MainActivity.this, R.string.msg_company_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            if (editCall != null) {
                // Update mode
                editCall.setPhoneNumber(phone);
                editCall.setCompanyName(company);
                editCall.setTags(tags);
                editCall.setRoundStatus(round);
                
                dbHelper.updateJobCall(editCall);
                Toast.makeText(MainActivity.this, "Log updated!", Toast.LENGTH_SHORT).show();
            } else {
                // Insert mode
                JobCall newCall = new JobCall(phone, company, round, tags, System.currentTimeMillis());
                dbHelper.insertJobCall(newCall);
                Toast.makeText(MainActivity.this, "Call logged successfully!", Toast.LENGTH_SHORT).show();
            }

            refreshDashboardList();
            alertDialog.dismiss();
        });

        // Apply rounded corner frame to dialog window
        if (alertDialog.getWindow() != null) {
            alertDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        alertDialog.show();
        
        // Wrap custom background shape on layout container inside alertDialog window
        View parent = (View) dialogView.getParent();
        if (parent != null) {
            parent.setBackgroundResource(R.drawable.spinner_border);
        }
    }

    /**
     * Checks if a phone number exists in the device's Contacts.
     */
    private boolean isContactExists(String number) {
        if (number == null || number.isEmpty()) {
            return false;
        }
        try {
            Uri lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number)
            );
            String[] projection = { ContactsContract.PhoneLookup._ID };
            try (Cursor cursor = getContentResolver().query(lookupUri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Helper to write contact details directly into the phone's address book.
     */
    private boolean saveContactDirectly(String name, String phoneNumber) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        int rawContactInsertIndex = ops.size();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build());

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, 
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build());

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, 
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, 
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build());
        try {
            getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ALL_PERMISSIONS_REQUEST_CODE) {
            checkPermissionsBannerVisibility();
        }
    }
}

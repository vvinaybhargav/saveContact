package com.example.callsaver;

import android.content.ContentProviderOperation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class SaveContactActivity extends AppCompatActivity {

    private String phoneNumber;
    private EditText etContactName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Show over lockscreen if device is locked
        configureShowWhenLocked();
        
        setContentView(R.layout.activity_save_contact);

        phoneNumber = getIntent().getStringExtra("phone_number");
        if (phoneNumber == null) {
            finish();
            return;
        }

        TextView tvPhoneNumber = findViewById(R.id.tv_phone_number);
        etContactName = findViewById(R.id.et_contact_name);
        Button btnDismiss = findViewById(R.id.btn_dismiss);
        Button btnSave = findViewById(R.id.btn_save);
        View rootLayout = findViewById(R.id.root_layout);

        tvPhoneNumber.setText(phoneNumber);

        // Dismiss when clicking outside the dialog card
        rootLayout.setOnClickListener(v -> finish());
        
        // Prevent dismissal when clicking the card itself (it's handled by hierarchy click propagation)
        View cardView = findViewById(R.id.root_layout).findViewWithTag("card");
        if (cardView != null) {
            cardView.setOnClickListener(v -> {
                // Do nothing, just intercept touch event so it doesn't propagate to rootLayout
            });
        }

        btnDismiss.setOnClickListener(v -> finish());

        btnSave.setOnClickListener(v -> {
            String name = etContactName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(SaveContactActivity.this, R.string.msg_name_empty, Toast.LENGTH_SHORT).show();
            } else {
                if (saveContactDirectly(name, phoneNumber)) {
                    Toast.makeText(SaveContactActivity.this, R.string.msg_contact_saved, Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(SaveContactActivity.this, R.string.msg_contact_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Request keyboard focus automatically
        etContactName.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        
        // Helper fallback to force keyboard layout to slide up after layout finishes
        etContactName.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etContactName, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);
    }

    private void configureShowWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    private boolean saveContactDirectly(String name, String phoneNumber) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        // 1. Raw contact insert
        int rawContactInsertIndex = ops.size();
        ops.add(ContentProviderOperation.newInsert(android.provider.ContactsContract.RawContacts.CONTENT_URI)
                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build());

        // 2. Structured name insert linked to raw contact
        ops.add(ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(android.provider.ContactsContract.Data.MIMETYPE, 
                        android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build());

        // 3. Phone number insert linked to raw contact
        ops.add(ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(android.provider.ContactsContract.Data.MIMETYPE, 
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE, 
                        android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build());

        try {
            getContentResolver().applyBatch(android.provider.ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}

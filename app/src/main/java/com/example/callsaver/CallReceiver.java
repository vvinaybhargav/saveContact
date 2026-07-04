package com.example.callsaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;
import android.util.Log;

public class CallReceiver extends BroadcastReceiver {

    private static final String TAG = "CallReceiver";
    private static final String PREFS_NAME = "CallSaverPrefs";
    private static final String KEY_LAST_STATE = "last_state";
    private static final String KEY_INCOMING_NUMBER = "incoming_number";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }

        String stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (stateStr == null) {
            return;
        }

        Log.d(TAG, "Phone State Changed: " + stateStr);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastSavedState = prefs.getString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_IDLE);

        if (stateStr.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
            // Incoming call - get phone number (only available in RINGING state on most devices)
            String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            if (incomingNumber != null && !incomingNumber.isEmpty()) {
                prefs.edit()
                        .putString(KEY_INCOMING_NUMBER, incomingNumber)
                        .putString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_RINGING)
                        .apply();
                Log.d(TAG, "Incoming call detected from number: " + incomingNumber);
            }
        } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
            // Call active/answered
            prefs.edit().putString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK).apply();
            Log.d(TAG, "Call active (OFFHOOK)");
        } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
            // Call ended/idle - check transition from active call
            Log.d(TAG, "Call ended (IDLE). Previous saved state: " + lastSavedState);
            
            if (lastSavedState.equals(TelephonyManager.EXTRA_STATE_RINGING) || 
                lastSavedState.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
                
                String incomingNumber = prefs.getString(KEY_INCOMING_NUMBER, null);
                Log.d(TAG, "Processing call completion. Stored number: " + incomingNumber);

                if (incomingNumber != null && !incomingNumber.trim().isEmpty()) {
                    if (!isContactExists(context, incomingNumber)) {
                        Log.d(TAG, "Number not in contacts. Triggering popup Activity.");
                        
                        // Launch SaveContactActivity popup
                        Intent popupIntent = new Intent(context, SaveContactActivity.class);
                        popupIntent.putExtra("phone_number", incomingNumber);
                        popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        popupIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        popupIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        context.startActivity(popupIntent);
                    } else {
                        Log.d(TAG, "Number already exists in contacts.");
                    }
                }
            }
            
            // Clean up state
            prefs.edit()
                    .remove(KEY_INCOMING_NUMBER)
                    .putString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_IDLE)
                    .apply();
        }
    }

    /**
     * Queries the Android Contacts ContentProvider to see if the given phone number exists.
     */
    private boolean isContactExists(Context context, String number) {
        if (number == null || number.isEmpty()) {
            return false;
        }

        try {
            Uri lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number)
            );
            String[] projection = { ContactsContract.PhoneLookup._ID };

            try (Cursor cursor = context.getContentResolver().query(lookupUri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException checking contacts: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception checking contacts: " + e.getMessage());
        }
        return false;
    }
}

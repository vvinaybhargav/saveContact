package com.example.callsaver;

import android.os.Build;
import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.util.Log;

import androidx.annotation.NonNull;

public class CallSaverScreeningService extends CallScreeningService {
    private static final String TAG = "CallScreeningService";

    @Override
    public void onScreenCall(@NonNull Call.Details callDetails) {
        // We only screen calls on Android 10 (Q) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String phoneNumber = "";
            if (callDetails.getHandle() != null) {
                phoneNumber = callDetails.getHandle().getSchemeSpecificPart();
            }

            Log.d(TAG, "Screening call: " + phoneNumber);

            if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
                boolean isOutgoing = callDetails.getCallDirection() == Call.Details.DIRECTION_OUTGOING;

                // Save call metadata details so CallReceiver has reference upon call completion (IDLE state)
                android.content.SharedPreferences prefs = getSharedPreferences("CallSaverPrefs", MODE_PRIVATE);
                prefs.edit()
                        .putString("incoming_number", phoneNumber)
                        .putString("last_state", isOutgoing ? "OUTGOING" : "RINGING")
                        .putBoolean("answered", isOutgoing)
                        .apply();

                // Call UI is now handled exclusively by CallSaverInCallService's
                // onCallAdded(), which launches the full-screen InCallActivity - no
                // separate overlay banner needed here anymore.
            }

            // Respond to the system to let the call proceed normally
            CallResponse response = new CallResponse.Builder()
                    .setDisallowCall(false)
                    .setRejectCall(false)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                    .build();
            respondToCall(callDetails, response);
        }
    }
}

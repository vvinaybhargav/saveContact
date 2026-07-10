package com.example.callsaver;

import android.content.Intent;
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
                // Show overlay banner for all calls
                DatabaseHelper db = new DatabaseHelper(this);
                JobCall jobCall = db.getJobCallByNumber(this, phoneNumber);

                Intent overlayIntent = new Intent(this, CallerIdService.class);
                overlayIntent.putExtra("phone_number", phoneNumber);
                if (jobCall != null) {
                    overlayIntent.putExtra("company_name", jobCall.getCompanyName());
                    overlayIntent.putExtra("round_status", jobCall.getRoundStatus());
                    overlayIntent.putExtra("tags", jobCall.getTags());
                    overlayIntent.putExtra("job_call_id", (long) jobCall.getId());
                    overlayIntent.putExtra("recruiter_name", jobCall.getRecruiterName());
                } else {
                    overlayIntent.putExtra("company_name", "Unknown Recruiter");
                    overlayIntent.putExtra("round_status", "Not Saved");
                    overlayIntent.putExtra("tags", "");
                    overlayIntent.putExtra("job_call_id", -1L);
                    overlayIntent.putExtra("recruiter_name", "");
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(overlayIntent);
                } else {
                    startService(overlayIntent);
                }
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

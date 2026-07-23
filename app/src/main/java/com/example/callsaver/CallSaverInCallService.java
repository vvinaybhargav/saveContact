package com.example.callsaver;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.InCallService;

/**
 * Required InCallService implementation so Android OS identifies CallSaver
 * as a valid system Default Dialer app in System Settings > Default Apps > Phone App dropdown.
 */
public class CallSaverInCallService extends InCallService {

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        if (call == null) return;

        String phoneNumber = "";
        if (call.getDetails() != null && call.getDetails().getHandle() != null) {
            phoneNumber = call.getDetails().getHandle().getSchemeSpecificPart();
        }

        if (phoneNumber != null && !phoneNumber.isEmpty()) {
            DatabaseHelper db = new DatabaseHelper(this);
            JobCall matchedCall = db.getJobCallByNumber(this, phoneNumber);

            Intent intent = new Intent(this, CallerIdService.class);
            intent.putExtra("phone_number", phoneNumber);
            if (matchedCall != null) {
                intent.putExtra("company_name", matchedCall.getCompanyName());
                intent.putExtra("round_status", matchedCall.getRoundStatus());
                intent.putExtra("tags", matchedCall.getTags());
                intent.putExtra("job_call_id", matchedCall.getId());
                intent.putExtra("recruiter_name", matchedCall.getRecruiterName());
            } else {
                intent.putExtra("company_name", "Recruiter Lead");
                intent.putExtra("round_status", "Screening");
                intent.putExtra("job_call_id", -1L);
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Intent intent = new Intent(this, CallerIdService.class);
        stopService(intent);
    }
}

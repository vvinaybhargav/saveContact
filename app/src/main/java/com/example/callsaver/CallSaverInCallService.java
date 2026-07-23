package com.example.callsaver;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

/**
 * Required InCallService implementation so Android OS identifies CallSaver as a valid
 * system Default Dialer app, and drives our own full-screen in-call UI (InCallActivity)
 * instead of the small floating banner - this is now the single source of truth for
 * "a call is active," replacing the old CallReceiver-triggered overlay.
 */
public class CallSaverInCallService extends InCallService {

    private static CallSaverInCallService instance;
    private static Call activeCall;

    private final Call.Callback callStateCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            super.onStateChanged(call, state);
            InCallActivity.notifyCallStateChanged(state);
            if (state == Call.STATE_DISCONNECTED) {
                InCallActivity.finishIfOpen();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) instance = null;
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        if (call == null) return;

        activeCall = call;
        call.registerCallback(callStateCallback);

        String phoneNumber = "";
        if (call.getDetails() != null && call.getDetails().getHandle() != null) {
            phoneNumber = call.getDetails().getHandle().getSchemeSpecificPart();
        }
        if (phoneNumber == null) phoneNumber = "";

        DatabaseHelper db = new DatabaseHelper(this);
        JobCall matchedCall = phoneNumber.isEmpty() ? null : db.getJobCallByNumber(this, phoneNumber);

        Intent intent = new Intent(this, InCallActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("phone_number", phoneNumber);
        intent.putExtra("initial_state", call.getState());
        if (matchedCall != null) {
            intent.putExtra("company_name", matchedCall.getCompanyName());
            intent.putExtra("round_status", matchedCall.getRoundStatus());
            intent.putExtra("tags", matchedCall.getTags());
            intent.putExtra("job_call_id", (long) matchedCall.getId());
            intent.putExtra("recruiter_name", matchedCall.getRecruiterName());
        } else {
            intent.putExtra("company_name", "Recruiter Lead");
            intent.putExtra("round_status", "First time");
            intent.putExtra("job_call_id", -1L);
        }
        startActivity(intent);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        if (activeCall != null) {
            try {
                activeCall.unregisterCallback(callStateCallback);
            } catch (Exception ignored) {
            }
        }
        activeCall = null;
        InCallActivity.finishIfOpen();
    }

    public static Call getActiveCall() {
        return activeCall;
    }

    public static void answer() {
        if (activeCall != null) activeCall.answer(0);
    }

    public static void reject() {
        if (activeCall != null) activeCall.reject(false, null);
    }

    public static void hangUp() {
        if (activeCall != null) activeCall.disconnect();
    }

    public static void setMuted(boolean muted) {
        if (instance != null) instance.setMuted(muted);
    }

    public static void setSpeakerOn(boolean on) {
        if (instance != null) {
            instance.setAudioRoute(on ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_EARPIECE);
        }
    }

    public static boolean isMuted() {
        if (instance != null) {
            CallAudioState state = instance.getCallAudioState();
            return state != null && state.isMuted();
        }
        return false;
    }

    public static boolean isSpeakerOn() {
        if (instance != null) {
            CallAudioState state = instance.getCallAudioState();
            return state != null && state.getRoute() == CallAudioState.ROUTE_SPEAKER;
        }
        return false;
    }
}

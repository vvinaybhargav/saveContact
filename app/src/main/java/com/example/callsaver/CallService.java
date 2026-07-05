package com.example.callsaver;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

/**
 * The InCallService the OS binds to once this app is the default phone app.
 * It is notified of every call and launches the full-screen {@link CallActivity}.
 * Audio controls are exposed statically so the activity can drive them.
 */
public class CallService extends InCallService {

    private static CallService sInstance;

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        sInstance = this;
        OngoingCall.setCall(call);

        Intent intent = new Intent(this, CallActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        OngoingCall.setCall(null);
        if (getCalls() == null || getCalls().isEmpty()) {
            sInstance = null;
        }
    }

    static void applyMute(boolean muted) {
        if (sInstance != null) {
            sInstance.setMuted(muted);
        }
    }

    static void applySpeaker(boolean on) {
        if (sInstance != null) {
            sInstance.setAudioRoute(on ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_EARPIECE);
        }
    }
}

package com.example.callsaver;

import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.VideoProfile;

/**
 * Static bridge between the {@link CallService} (InCallService) and the call UI
 * ({@link CallActivity}). Holds the single active call, forwards state changes to
 * a listener, and exposes answer / reject / hangup.
 */
public final class OngoingCall {

    public interface Listener {
        void onStateChanged(int state);
    }

    private static Call sCall;
    private static Listener sListener;

    private static final Call.Callback CALLBACK = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            if (sListener != null) {
                sListener.onStateChanged(state);
            }
        }
    };

    private OngoingCall() {
    }

    public static void setCall(Call call) {
        if (sCall != null) {
            sCall.unregisterCallback(CALLBACK);
        }
        sCall = call;
        if (sCall != null) {
            sCall.registerCallback(CALLBACK);
        }
    }

    public static void setListener(Listener listener) {
        sListener = listener;
    }

    public static boolean hasCall() {
        return sCall != null;
    }

    public static int getState() {
        if (sCall == null) {
            return Call.STATE_DISCONNECTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return sCall.getDetails().getState();
        }
        return sCall.getState();
    }

    public static String getNumber() {
        if (sCall == null || sCall.getDetails() == null) {
            return "";
        }
        Uri handle = sCall.getDetails().getHandle();
        return handle != null ? handle.getSchemeSpecificPart() : "";
    }

    public static long getConnectTimeMillis() {
        if (sCall == null || sCall.getDetails() == null) {
            return 0;
        }
        return sCall.getDetails().getConnectTimeMillis();
    }

    public static void answer() {
        if (sCall != null) {
            sCall.answer(VideoProfile.STATE_AUDIO_ONLY);
        }
    }

    public static void reject() {
        if (sCall != null) {
            sCall.reject(false, null);
        }
    }

    public static void hangup() {
        if (sCall != null) {
            sCall.disconnect();
        }
    }
}

package com.example.callsaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import androidx.core.app.NotificationCompat;

/**
 * The InCallService the OS binds to once this app is the default phone app.
 * Shows the full-screen {@link CallActivity} via a full-screen-intent notification
 * (the reliable way to surface an incoming call from the background), and exposes
 * audio controls to the activity. Everything is guarded so the service never crashes
 * the call (which would hand the call back to the stock phone app).
 */
public class CallService extends InCallService {

    private static CallService sInstance;
    private static final String CHANNEL_ID = "ongoing_call_channel";
    private static final int CALL_NOTIF_ID = 42;

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        sInstance = this;
        OngoingCall.setCall(call);
        // Incoming calls start RINGING; outgoing start CONNECTING/DIALING.
        try {
            int state = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? call.getDetails().getState() : call.getState();
            OngoingCall.setDirection(state == Call.STATE_RINGING
                    ? OngoingCall.DIR_INCOMING : OngoingCall.DIR_OUTGOING);
        } catch (Exception ignored) {
        }
        try {
            showCallUi(call);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        try {
            logCallHistory(call);
        } catch (Exception e) {
            e.printStackTrace();
        }
        OngoingCall.setCall(null);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(CALL_NOTIF_ID);
            }
        } catch (Exception ignored) {
        }
        if (getCalls() == null || getCalls().isEmpty()) {
            sInstance = null;
        }
    }

    private void showCallUi(Call call) {
        Intent intent = new Intent(this, CallActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, piFlags);

        boolean ringing = false;
        String number = "";
        try {
            int state = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? call.getDetails().getState() : call.getState();
            ringing = state == Call.STATE_RINGING;
            Uri handle = call.getDetails().getHandle();
            if (handle != null) {
                number = handle.getSchemeSpecificPart();
            }
        } catch (Exception ignored) {
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Ongoing calls", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Shows the active call screen.");
            nm.createNotificationChannel(channel);
        }

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(ringing ? "Incoming call" : "Ongoing call")
                .setContentText(number.isEmpty() ? "Call in progress" : number)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .build();

        try {
            nm.notify(CALL_NOTIF_ID, n);
        } catch (Exception ignored) {
        }

        // Direct launch too (works when the app is already in the foreground / outgoing).
        try {
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /**
     * Logs this call against its tracked job entry (if the number is tracked).
     */
    private void logCallHistory(Call call) {
        if (call == null || call.getDetails() == null) {
            return;
        }
        String number = "";
        Uri handle = call.getDetails().getHandle();
        if (handle != null) {
            number = handle.getSchemeSpecificPart();
        }
        if (number.isEmpty()) {
            return;
        }

        DatabaseHelper db = new DatabaseHelper(this);
        JobCall job = db.getJobCallByNumber(this, number);
        if (job == null || job.getId() <= 0) {
            return; // only log for tracked entries
        }

        long connectTime = call.getDetails().getConnectTimeMillis();
        boolean answered = connectTime > 0;
        int duration = answered ? (int) Math.max(0, (System.currentTimeMillis() - connectTime) / 1000) : 0;

        String type;
        if (OngoingCall.getDirection() == OngoingCall.DIR_OUTGOING) {
            type = "Outgoing";
        } else {
            type = answered ? "Incoming" : "Missed";
        }
        db.insertCallHistory(job.getId(), type, duration, System.currentTimeMillis());
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

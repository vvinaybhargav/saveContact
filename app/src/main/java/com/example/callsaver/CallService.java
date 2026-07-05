package com.example.callsaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

import java.util.List;

/**
 * The InCallService the OS binds to once this app is the default phone app.
 * Shows the full-screen {@link CallActivity} via a full-screen-intent notification
 * (the reliable way to surface an incoming call from the background), and exposes
 * audio controls to the activity. Everything is guarded so the service never crashes
 * the call (which would hand the call back to the stock phone app).
 */
public class CallService extends InCallService {

    private static CallService sInstance;
    // New channel id (v2) so a fresh HIGH-importance channel is created; the old one
    // may be stuck at Default importance, which makes sound but never peeks/heads-up.
    private static final String CHANNEL_ID = "incoming_calls_v2";
    static final int CALL_NOTIF_ID = 42;

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

        // Name + subtitle (round · tags · latest note) for the notification / heads-up.
        String[] info = resolveCallerInfo(number, ringing ? "Incoming call" : "Ongoing call");
        String name = info[0];
        String subtitle = info[1];

        Intent fullScreen = new Intent(this, CallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, fullScreen, piFlags());

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Shows the incoming-call banner at the top of the screen.");
            channel.enableVibration(true);
            channel.enableLights(true);
            channel.setShowBadge(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(channel);
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .setColorized(true)
                .setColor(0xFF6366F1)
                .setFullScreenIntent(contentPi, true)
                .setContentIntent(contentPi);

        if (ringing) {
            Intent answerAct = new Intent(this, CallActivity.class)
                    .putExtra("action", "answer")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent answerPi = PendingIntent.getActivity(this, 1, answerAct, piFlags());
            Intent declineBc = new Intent(this, CallActionReceiver.class)
                    .setAction(CallActionReceiver.ACTION_DECLINE);
            PendingIntent declinePi = PendingIntent.getBroadcast(this, 2, declineBc, piFlags());

            // Official incoming-call style: the system shows this prominently as a
            // heads-up banner with Answer/Decline (far more reliable than a plain one).
            Person caller = new Person.Builder().setName(name).build();
            b.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, answerPi));
            b.setContentText(subtitle);
        } else {
            b.setContentTitle(name)
                    .setContentText(subtitle)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(subtitle));
        }

        try {
            nm.notify(CALL_NOTIF_ID, b.build());
        } catch (Exception ignored) {
        }

        // Outgoing calls are user-initiated -> open the full screen immediately.
        // Incoming calls rely on the full-screen intent: heads-up when unlocked,
        // full-screen when locked/off. So we do NOT force the activity here.
        if (!ringing) {
            try {
                startActivity(fullScreen);
            } catch (Exception ignored) {
            }
        }
    }

    private int piFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    /**
     * Returns [displayName, subtitle] for the call notification. Subtitle is the
     * round · tags · latest note for a tracked caller, else the default.
     */
    private String[] resolveCallerInfo(String number, String defaultSubtitle) {
        String name = (number == null || number.isEmpty()) ? "Unknown" : number;
        String subtitle = defaultSubtitle;
        try {
            DatabaseHelper db = new DatabaseHelper(this);
            JobCall job = db.getJobCallByNumber(this, number);
            if (job != null) {
                if (job.getCompanyName() != null && !job.getCompanyName().trim().isEmpty()) {
                    name = job.getCompanyName();
                }
                StringBuilder sb = new StringBuilder();
                if (job.getRoundStatus() != null && !job.getRoundStatus().trim().isEmpty()) {
                    sb.append(job.getRoundStatus());
                }
                if (job.getTags() != null && !job.getTags().trim().isEmpty()) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(job.getTags());
                }
                List<CallNote> notes = db.getNotesForJob(job.getId());
                if (!notes.isEmpty() && notes.get(0).note != null && !notes.get(0).note.trim().isEmpty()) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(notes.get(0).note);
                }
                if (sb.length() > 0) {
                    subtitle = sb.toString();
                }
            } else {
                String contactName = lookupContactName(number);
                if (contactName != null && !contactName.trim().isEmpty()) {
                    name = contactName;
                }
            }
        } catch (Exception ignored) {
        }
        return new String[]{name, subtitle};
    }

    private String lookupContactName(String number) {
        if (number == null || number.isEmpty()) {
            return null;
        }
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            String[] projection = {ContactsContract.PhoneLookup.DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    return c.getString(0);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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

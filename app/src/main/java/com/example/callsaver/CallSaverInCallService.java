package com.example.callsaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import androidx.core.app.NotificationCompat;

/**
 * Required InCallService implementation so Android OS identifies CallSaver as a valid
 * system Default Dialer app, and drives our own full-screen in-call UI (InCallActivity)
 * instead of the small floating banner - this is now the single source of truth for
 * "a call is active," replacing the old CallReceiver-triggered overlay.
 *
 * Incoming (ringing) calls are routed through a high-priority notification with a
 * full-screen intent: Android launches InCallActivity full-screen automatically when
 * the device is locked/idle, but only shows a non-intrusive heads-up banner when the
 * screen is already on and in use - so the call UI never interrupts active use of the
 * phone. Outgoing calls (the user just dialed, so they're already looking at the
 * screen) launch InCallActivity directly.
 */
public class CallSaverInCallService extends InCallService {

    private static final String CHANNEL_ID = "incoming_call_channel";
    private static final String POST_CALL_CHANNEL_ID = "post_call_log_channel";
    private static final String ONGOING_CALL_CHANNEL_ID = "ongoing_call_channel";
    private static final int ONGOING_CALL_NOTIFICATION_ID = 8001;

    private static CallSaverInCallService instance;
    private static Call activeCall;

    private final Call.Callback callStateCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            super.onStateChanged(call, state);
            InCallActivity.notifyCallStateChanged(state);
            if (state != Call.STATE_RINGING) {
                cancelIncomingCallNotification();
            }
            if (state == Call.STATE_ACTIVE || state == Call.STATE_DIALING || state == Call.STATE_CONNECTING) {
                // Answered from the notification, or the call otherwise became active
                // while InCallActivity wasn't already showing - bring it up now.
                launchInCallActivity(call);
                // Keep a persistent "call in progress" notification while the call is
                // live, so backgrounding the app (home button) still leaves a way back
                // into the call screen instead of it seeming to vanish.
                showOngoingCallNotification(call);
            }
            if (state == Call.STATE_DISCONNECTED) {
                InCallActivity.finishIfOpen();
                cancelOngoingCallNotification();
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

        if (call.getState() == Call.STATE_RINGING) {
            showIncomingCallNotification(call);
        } else {
            launchInCallActivity(call);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        String endedNumber = resolvePhoneNumber(call);
        if (activeCall != null) {
            try {
                activeCall.unregisterCallback(callStateCallback);
            } catch (Exception ignored) {
            }
        }
        activeCall = null;
        cancelIncomingCallNotification();
        cancelOngoingCallNotification();
        InCallActivity.finishIfOpen();
        showPostCallNotification(endedNumber);
    }

    /**
     * After a call ends, nudge the user to log details - tapping opens the SAME
     * capture screen (InCallActivity in review mode) they'd use during the call, so
     * the note-taking experience is identical whether logged live or after the fact.
     */
    private void showPostCallNotification(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    POST_CALL_CHANNEL_ID, "Log call after ending", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Prompts you to log recruiter call details after a call ends.");
            nm.createNotificationChannel(channel);
        }

        DatabaseHelper db = new DatabaseHelper(this);
        JobCall matchedCall = db.getJobCallByNumber(this, phoneNumber);

        // Plain saved phone-contact with no recruiter/job-lead data - nothing to log.
        if (matchedCall == null) {
            String contactOnly = TrackerFragment.getContactNameByNumber(this, phoneNumber);
            if (contactOnly != null && !contactOnly.isEmpty()) return;
        }

        String label = resolveNameAndNumber(phoneNumber, matchedCall);

        Intent tapIntent = new Intent(this, InCallActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        tapIntent.putExtra("mode", "review");
        tapIntent.putExtra("phone_number", phoneNumber);
        if (matchedCall != null) {
            tapIntent.putExtra("company_name", matchedCall.getCompanyName());
            tapIntent.putExtra("round_status", matchedCall.getRoundStatus());
            tapIntent.putExtra("job_call_id", (long) matchedCall.getId());
            tapIntent.putExtra("recruiter_name", matchedCall.getRecruiterName());
        } else {
            tapIntent.putExtra("contact_name", label);
            tapIntent.putExtra("job_call_id", -1L);
        }

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, phoneNumber.hashCode() + 700, tapIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(this, POST_CALL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("Log call with " + label)
                .setContentText("Tap to add notes, round, next call, CTC…")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();

        try {
            nm.notify(phoneNumber.hashCode() + 700, notification);
        } catch (SecurityException ignored) {
        }
    }

    private String resolvePhoneNumber(Call call) {
        if (call != null && call.getDetails() != null && call.getDetails().getHandle() != null) {
            String number = call.getDetails().getHandle().getSchemeSpecificPart();
            return number != null ? number : "";
        }
        return "";
    }

    /** Resolves which SIM a call is on (dual-SIM devices), e.g. "SIM 1" / "SIM 2". */
    private String resolveSimLabel(Call call) {
        try {
            if (call == null || call.getDetails() == null) return "";
            android.telecom.PhoneAccountHandle handle = call.getDetails().getAccountHandle();
            if (handle == null) return "";
            android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm == null) return "";
            java.util.List<android.telecom.PhoneAccountHandle> allHandles = tm.getCallCapablePhoneAccounts();
            if (allHandles != null) {
                int index = allHandles.indexOf(handle);
                if (index >= 0) return "SIM " + (index + 1);
            }
            android.telecom.PhoneAccount account = tm.getPhoneAccount(handle);
            if (account != null && account.getLabel() != null) return account.getLabel().toString();
        } catch (SecurityException ignored) {
        }
        return "";
    }

    private Intent buildInCallIntent(Call call) {
        String phoneNumber = resolvePhoneNumber(call);
        DatabaseHelper db = new DatabaseHelper(this);
        JobCall matchedCall = phoneNumber.isEmpty() ? null : db.getJobCallByNumber(this, phoneNumber);

        Intent intent = new Intent(this, InCallActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("phone_number", phoneNumber);
        intent.putExtra("sim_label", resolveSimLabel(call));
        intent.putExtra("initial_state", call != null ? call.getState() : Call.STATE_ACTIVE);
        if (matchedCall != null) {
            intent.putExtra("company_name", matchedCall.getCompanyName());
            intent.putExtra("round_status", matchedCall.getRoundStatus());
            intent.putExtra("tags", matchedCall.getTags());
            intent.putExtra("job_call_id", (long) matchedCall.getId());
            intent.putExtra("recruiter_name", matchedCall.getRecruiterName());
        } else {
            String contactName = TrackerFragment.getContactNameByNumber(this, phoneNumber);
            if (contactName != null && !contactName.isEmpty()) {
                intent.putExtra("contact_name", contactName);
            }
            intent.putExtra("round_status", "First time");
            intent.putExtra("job_call_id", -1L);
        }
        return intent;
    }

    private String resolveDisplayLabel(String phoneNumber, JobCall matchedCall) {
        if (matchedCall != null && matchedCall.getCompanyName() != null && !matchedCall.getCompanyName().isEmpty()) {
            return matchedCall.getCompanyName();
        }
        String contactName = TrackerFragment.getContactNameByNumber(this, phoneNumber);
        if (contactName != null && !contactName.isEmpty()) {
            return contactName;
        }
        return phoneNumber.isEmpty() ? "Unknown" : phoneNumber;
    }

    /** Name (if any) plus the raw number, for consistent "who's calling" text everywhere. */
    private String resolveNameAndNumber(String phoneNumber, JobCall matchedCall) {
        String label = resolveDisplayLabel(phoneNumber, matchedCall);
        if (label.equals(phoneNumber) || phoneNumber.isEmpty()) return label;
        return label + " • " + phoneNumber;
    }

    private void launchInCallActivity(Call call) {
        startActivity(buildInCallIntent(call));
    }

    /** Used by CallNotificationActionReceiver so tapping "Answer" in the notification
     *  actually brings the full-screen call UI to front, not just answers silently. */
    public static void bringInCallUiToFront() {
        if (instance != null && activeCall != null) {
            instance.launchInCallActivity(activeCall);
        }
    }

    private void showIncomingCallNotification(Call call) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Incoming call alerts - full screen when idle/locked, heads-up otherwise.");
            nm.createNotificationChannel(channel);
        }

        String phoneNumber = resolvePhoneNumber(call);
        DatabaseHelper db = new DatabaseHelper(this);
        JobCall matchedCall = phoneNumber.isEmpty() ? null : db.getJobCallByNumber(this, phoneNumber);
        String title = phoneNumber.isEmpty() ? "Incoming call" : resolveNameAndNumber(phoneNumber, matchedCall);
        String simLabel = resolveSimLabel(call);
        String content = matchedCall != null && matchedCall.getRoundStatus() != null && !matchedCall.getRoundStatus().isEmpty()
                ? "Incoming call - " + matchedCall.getRoundStatus() : "Incoming call";
        if (!simLabel.isEmpty()) content += "  ·  " + simLabel;

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_IMMUTABLE;

        Intent fullScreenIntent = buildInCallIntent(call);
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                this, CallNotificationActionReceiver.NOTIFICATION_ID, fullScreenIntent, piFlags);

        Intent answerIntent = new Intent(this, CallNotificationActionReceiver.class)
                .setAction(CallNotificationActionReceiver.ACTION_ANSWER);
        PendingIntent answerPendingIntent = PendingIntent.getBroadcast(
                this, CallNotificationActionReceiver.NOTIFICATION_ID, answerIntent, piFlags);

        Intent declineIntent = new Intent(this, CallNotificationActionReceiver.class)
                .setAction(CallNotificationActionReceiver.ACTION_DECLINE);
        PendingIntent declinePendingIntent = PendingIntent.getBroadcast(
                this, CallNotificationActionReceiver.NOTIFICATION_ID + 1, declineIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)
                .addAction(android.R.drawable.sym_action_call, "Answer", answerPendingIntent)
                .build();

        try {
            nm.notify(CallNotificationActionReceiver.NOTIFICATION_ID, notification);
        } catch (SecurityException ignored) {
        }
    }

    private void cancelIncomingCallNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(CallNotificationActionReceiver.NOTIFICATION_ID);
    }

    /**
     * Ongoing "call in progress, tap to return" notification shown while a call is
     * active/dialing/connecting - lets the user get back to InCallActivity after
     * backgrounding the app, since there's otherwise no way back into the call screen.
     */
    private void showOngoingCallNotification(Call call) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ONGOING_CALL_CHANNEL_ID, "Call in progress", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Ongoing notification while a call is active - tap to return to the call screen.");
            nm.createNotificationChannel(channel);
        }

        String phoneNumber = resolvePhoneNumber(call);
        DatabaseHelper db = new DatabaseHelper(this);
        JobCall matchedCall = phoneNumber.isEmpty() ? null : db.getJobCallByNumber(this, phoneNumber);
        String label = resolveNameAndNumber(phoneNumber, matchedCall);
        String simLabel = resolveSimLabel(call);

        Intent tapIntent = buildInCallIntent(call);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, ONGOING_CALL_NOTIFICATION_ID, tapIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(this, ONGOING_CALL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Call in progress - " + label)
                .setContentText(simLabel.isEmpty() ? "Tap to return to the call screen" : "Tap to return  ·  " + simLabel)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();

        try {
            nm.notify(ONGOING_CALL_NOTIFICATION_ID, notification);
        } catch (SecurityException ignored) {
        }
    }

    private void cancelOngoingCallNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ONGOING_CALL_NOTIFICATION_ID);
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

    public static void setCallMuted(boolean muted) {
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

    /** Sends a DTMF tone for the given key (e.g. for IVR menus/extensions mid-call). */
    public static void playDtmfTone(char digit) {
        if (activeCall != null) activeCall.playDtmfTone(digit);
    }

    public static void stopDtmfTone() {
        if (activeCall != null) activeCall.stopDtmfTone();
    }
}

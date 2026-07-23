package com.example.callsaver;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Handles Answer/Decline tapped directly from the incoming-call heads-up notification
 * (shown instead of the full-screen InCallActivity when the screen is already on and in
 * use, via setFullScreenIntent's "only launch full-screen if idle/locked" behavior).
 */
public class CallNotificationActionReceiver extends BroadcastReceiver {

    public static final String ACTION_ANSWER = "com.example.callsaver.action.NOTIF_ANSWER";
    public static final String ACTION_DECLINE = "com.example.callsaver.action.NOTIF_DECLINE";
    public static final int NOTIFICATION_ID = 6001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (ACTION_ANSWER.equals(intent.getAction())) {
            CallSaverInCallService.answer();
            CallSaverInCallService.bringInCallUiToFront();
        } else if (ACTION_DECLINE.equals(intent.getAction())) {
            CallSaverInCallService.reject();
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }
}

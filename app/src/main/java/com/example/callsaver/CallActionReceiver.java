package com.example.callsaver;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Handles the Answer / Decline actions on the incoming-call heads-up notification
 * so the user can act without opening the full-screen call screen.
 */
public class CallActionReceiver extends BroadcastReceiver {

    public static final String ACTION_ANSWER = "com.example.callsaver.action.ANSWER";
    public static final String ACTION_DECLINE = "com.example.callsaver.action.DECLINE";
    public static final String ACTION_HANGUP = "com.example.callsaver.action.HANGUP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_ANSWER.equals(action)) {
            OngoingCall.answer();
        } else if (ACTION_DECLINE.equals(action)) {
            OngoingCall.reject();
        } else if (ACTION_HANGUP.equals(action)) {
            OngoingCall.hangup();
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(CallService.CALL_NOTIF_ID);
        }
    }
}

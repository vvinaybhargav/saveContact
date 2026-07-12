package com.example.callsaver;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Detects interviews whose tentative_schedule date/time has already passed but the
 * round status hasn't been updated since - i.e. calls that need a follow-up update.
 */
public class FollowUpUtils {

    private static final Set<String> TERMINAL_STATUSES = new HashSet<>();
    static {
        TERMINAL_STATUSES.add("Negative");
        TERMINAL_STATUSES.add("Not Interested");
        TERMINAL_STATUSES.add("Offered");
    }

    private static final SimpleDateFormat SCHEDULE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd 'at' hh:mm a", Locale.getDefault());

    /** Grace period after the scheduled call time before we push a reminder notification. */
    public static final long NOTIFICATION_DELAY_MILLIS = 2 * 60 * 60 * 1000L;

    /** Parses a tentative_schedule string into millis, or -1 if unparseable/empty. */
    public static long parseScheduleMillis(String schedule) {
        if (schedule == null || schedule.trim().isEmpty()) return -1;
        try {
            Date d = SCHEDULE_FORMAT.parse(schedule.trim());
            return d != null ? d.getTime() : -1;
        } catch (ParseException e) {
            return -1;
        }
    }

    /**
     * True if this call's scheduled interview time has already passed and the round
     * status is still an active (non-terminal) stage - meaning the user likely needs
     * to log what happened on the call.
     */
    public static boolean needsFollowUp(JobCall call) {
        if (call == null) return false;
        String status = call.getRoundStatus();
        if (status != null && TERMINAL_STATUSES.contains(status.trim())) return false;
        long scheduleMillis = parseScheduleMillis(call.getTentativeSchedule());
        if (scheduleMillis < 0) return false;
        return scheduleMillis < System.currentTimeMillis();
    }

    /**
     * True if this call needs a follow-up (see {@link #needsFollowUp}) AND at least
     * {@link #NOTIFICATION_DELAY_MILLIS} has passed since the scheduled time - used to
     * delay the push notification so it doesn't fire the instant a call is due.
     */
    public static boolean needsFollowUpNotification(JobCall call) {
        if (call == null) return false;
        String status = call.getRoundStatus();
        if (status != null && TERMINAL_STATUSES.contains(status.trim())) return false;
        long scheduleMillis = parseScheduleMillis(call.getTentativeSchedule());
        if (scheduleMillis < 0) return false;
        return System.currentTimeMillis() - scheduleMillis >= NOTIFICATION_DELAY_MILLIS;
    }
}

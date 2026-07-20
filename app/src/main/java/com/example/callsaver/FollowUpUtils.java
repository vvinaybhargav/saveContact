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

    private static final String[] PATTERNS = {
        "yyyy-MM-dd hh:mm a",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "dd MMM yyyy hh:mm a",
        "dd MMMM yyyy hh:mm a",
        "dd MMM yyyy HH:mm",
        "dd MMMM yyyy HH:mm",
        "MMM dd yyyy hh:mm a",
        "MMMM dd yyyy hh:mm a",
        "MMM dd yyyy HH:mm",
        "MMMM dd yyyy HH:mm",
        
        "dd MMM hh:mm a",
        "dd MMMM hh:mm a",
        "dd MMM HH:mm",
        "dd MMMM HH:mm",
        "MMM dd hh:mm a",
        "MMMM dd hh:mm a",
        "MMM dd HH:mm",
        "MMMM dd HH:mm",
        "dd MMM",
        "dd MMMM",
        "MMM dd",
        "MMMM dd"
    };

    /** Parses a tentative_schedule string into millis, or -1 if unparseable/empty. */
    public static long parseScheduleMillis(String schedule) {
        if (schedule == null || schedule.trim().isEmpty()) return -1;
        
        // Clean ordinal suffixes: 1st, 2nd, 3rd, 22nd -> 1, 2, 3, 22
        String clean = schedule.replaceAll("(?i)(\\d+)(st|nd|rd|th)", "$1");
        
        // Strip the word "at" surrounded by spaces
        clean = clean.replaceAll("(?i)\\bat\\b", " ");
        
        // Standardize time like "4pm" or "4 pm" to "4:00 pm"
        clean = clean.replaceAll("(?i)(?<![:.])\\b(\\d+)\\s*(am|pm)\\b", "$1:00 $2");
        
        // Clean multiple spaces
        clean = clean.replaceAll("\\s+", " ").trim();
        
        for (String pattern : PATTERNS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.getDefault());
                Date d = sdf.parse(clean);
                if (d != null) {
                    java.util.Calendar cal = java.util.Calendar.getInstance();
                    int currentYear = cal.get(java.util.Calendar.YEAR);
                    cal.setTime(d);
                    // If year is omitted (like 1970/1972 default), update to current calendar year
                    if (cal.get(java.util.Calendar.YEAR) < 2000) {
                        cal.set(java.util.Calendar.YEAR, currentYear);
                    }
                    return cal.getTimeInMillis();
                }
            } catch (ParseException ignored) {}
            
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.ENGLISH);
                Date d = sdf.parse(clean);
                if (d != null) {
                    java.util.Calendar cal = java.util.Calendar.getInstance();
                    int currentYear = cal.get(java.util.Calendar.YEAR);
                    cal.setTime(d);
                    if (cal.get(java.util.Calendar.YEAR) < 2000) {
                        cal.set(java.util.Calendar.YEAR, currentYear);
                    }
                    return cal.getTimeInMillis();
                }
            } catch (ParseException ignored) {}
        }
        return -1;
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

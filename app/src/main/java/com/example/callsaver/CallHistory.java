package com.example.callsaver;

/**
 * A single logged call against a job entry (one row of the call history).
 * type is "Incoming", "Outgoing", or "Missed"; duration is in seconds.
 */
public class CallHistory {
    public final long id;
    public final long jobCallId;
    public final String type;
    public final int duration;
    public final long timestamp;

    public CallHistory(long id, long jobCallId, String type, int duration, long timestamp) {
        this.id = id;
        this.jobCallId = jobCallId;
        this.type = type;
        this.duration = duration;
        this.timestamp = timestamp;
    }
}

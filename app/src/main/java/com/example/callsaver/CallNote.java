package com.example.callsaver;

/**
 * A single timestamped note attached to a job call (one row of the notes timeline).
 */
public class CallNote {
    public final long id;
    public final long jobCallId;
    public final String note;
    public final long timestamp;

    public CallNote(long id, long jobCallId, String note, long timestamp) {
        this.id = id;
        this.jobCallId = jobCallId;
        this.note = note;
        this.timestamp = timestamp;
    }
}

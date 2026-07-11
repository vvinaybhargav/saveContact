package com.example.callsaver;

/**
 * A single timestamped note attached to a job call (one row of the notes timeline).
 * source is DatabaseHelper.NOTE_SOURCE_CALL (auto/real call) or NOTE_SOURCE_MANUAL
 * (user manually uploaded & transcribed a recording from the edit-log screen).
 */
public class CallNote {
    public final long id;
    public final long jobCallId;
    public final String note;
    public final long timestamp;
    public final String source;

    public CallNote(long id, long jobCallId, String note, long timestamp) {
        this(id, jobCallId, note, timestamp, DatabaseHelper.NOTE_SOURCE_CALL);
    }

    public CallNote(long id, long jobCallId, String note, long timestamp, String source) {
        this.id = id;
        this.jobCallId = jobCallId;
        this.note = note;
        this.timestamp = timestamp;
        this.source = source == null ? DatabaseHelper.NOTE_SOURCE_CALL : source;
    }

    public boolean isManual() {
        return DatabaseHelper.NOTE_SOURCE_MANUAL.equals(source);
    }
}

package com.example.callsaver;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.telephony.PhoneNumberUtils;

import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "JobTracker.db";
    private static final int DATABASE_VERSION = 4; // V4: per-note timeline table

    public static final String TABLE_NAME = "job_calls";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_PHONE_NUMBER = "phone_number";
    public static final String COLUMN_COMPANY_NAME = "company_name";
    public static final String COLUMN_ROUND_STATUS = "round_status";
    public static final String COLUMN_TAGS = "tags";
    public static final String COLUMN_NOTES = "notes";
    public static final String COLUMN_DURATION = "duration"; // Added in V3
    public static final String COLUMN_TIMESTAMP = "timestamp";

    // V4: individual timestamped notes, one row per note, linked to a job call.
    public static final String TABLE_NOTES = "call_notes";
    public static final String COLUMN_NOTE_ID = "id";
    public static final String COLUMN_NOTE_JOB_ID = "job_call_id";
    public static final String COLUMN_NOTE_TEXT = "note_text";
    public static final String COLUMN_NOTE_TIME = "note_time";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_PHONE_NUMBER + " TEXT,"
                + COLUMN_COMPANY_NAME + " TEXT,"
                + COLUMN_ROUND_STATUS + " TEXT,"
                + COLUMN_TAGS + " TEXT,"
                + COLUMN_NOTES + " TEXT,"
                + COLUMN_DURATION + " INTEGER DEFAULT 0," // Duration column
                + COLUMN_TIMESTAMP + " INTEGER"
                + ")";
        db.execSQL(CREATE_TABLE);
        db.execSQL(createNotesTableSql());
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NOTES + " TEXT");
        }
        if (oldVersion < 3) {
            // Upgrade from V2 to V3: Add duration column safely
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_DURATION + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 4) {
            // V4: add the notes timeline table and migrate any existing notes blob into it.
            db.execSQL(createNotesTableSql());
            migrateBlobNotes(db);
        }
    }

    private static String createNotesTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_NOTES + "("
                + COLUMN_NOTE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_NOTE_JOB_ID + " INTEGER,"
                + COLUMN_NOTE_TEXT + " TEXT,"
                + COLUMN_NOTE_TIME + " INTEGER"
                + ")";
    }

    /**
     * One-time migration: copy each job's accumulated notes blob into a single
     * timeline note so nothing is lost when moving to the per-note model.
     */
    private void migrateBlobNotes(SQLiteDatabase db) {
        Cursor c = db.query(TABLE_NAME,
                new String[]{COLUMN_ID, COLUMN_NOTES, COLUMN_TIMESTAMP},
                null, null, null, null, null);
        if (c != null) {
            while (c.moveToNext()) {
                int id = c.getInt(0);
                String notes = c.getString(1);
                long ts = c.getLong(2);
                if (notes != null && !notes.trim().isEmpty()) {
                    ContentValues v = new ContentValues();
                    v.put(COLUMN_NOTE_JOB_ID, id);
                    v.put(COLUMN_NOTE_TEXT, notes.trim());
                    v.put(COLUMN_NOTE_TIME, ts > 0 ? ts : System.currentTimeMillis());
                    db.insert(TABLE_NOTES, null, v);
                }
            }
            c.close();
        }
    }

    /**
     * Inserts a new job call log into SQLite.
     */
    public long insertJobCall(JobCall jobCall) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_PHONE_NUMBER, jobCall.getPhoneNumber());
        values.put(COLUMN_COMPANY_NAME, jobCall.getCompanyName());
        values.put(COLUMN_ROUND_STATUS, jobCall.getRoundStatus());
        values.put(COLUMN_TAGS, jobCall.getTags());
        values.put(COLUMN_NOTES, jobCall.getNotes());
        values.put(COLUMN_DURATION, jobCall.getDuration());
        values.put(COLUMN_TIMESTAMP, jobCall.getTimestamp());

        long id = db.insert(TABLE_NAME, null, values);
        db.close();
        return id;
    }

    /**
     * Fetches all job call logs, sorted by newest first.
     */
    public List<JobCall> getAllJobCalls() {
        List<JobCall> callsList = new ArrayList<>();
        String selectQuery = "SELECT * FROM " + TABLE_NAME + " ORDER BY " + COLUMN_TIMESTAMP + " DESC";

        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);

        if (cursor.moveToFirst()) {
            do {
                JobCall call = new JobCall(
                        cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHONE_NUMBER)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_COMPANY_NAME)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ROUND_STATUS)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TAGS)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOTES)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_DURATION)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                );
                callsList.add(call);
            } while (cursor.moveToNext());
        }

        cursor.close();
        db.close();
        return callsList;
    }

    /**
     * Updates an existing job call entry.
     */
    public int updateJobCall(JobCall jobCall) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_PHONE_NUMBER, jobCall.getPhoneNumber());
        values.put(COLUMN_COMPANY_NAME, jobCall.getCompanyName());
        values.put(COLUMN_ROUND_STATUS, jobCall.getRoundStatus());
        values.put(COLUMN_TAGS, jobCall.getTags());
        values.put(COLUMN_NOTES, jobCall.getNotes());
        values.put(COLUMN_DURATION, jobCall.getDuration());
        values.put(COLUMN_TIMESTAMP, jobCall.getTimestamp());

        int count = db.update(TABLE_NAME, values, COLUMN_ID + " = ?",
                new String[]{String.valueOf(jobCall.getId())});
        db.close();
        return count;
    }

    /**
     * Deletes a job call log entry.
     */
    public void deleteJobCall(int id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_NAME, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
        db.close();
    }

    /**
     * Adds a timestamped note to a job call's timeline and refreshes the card preview.
     */
    public long insertNote(long jobCallId, String note, long timestamp) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COLUMN_NOTE_JOB_ID, jobCallId);
        v.put(COLUMN_NOTE_TEXT, note);
        v.put(COLUMN_NOTE_TIME, timestamp);
        long id = db.insert(TABLE_NOTES, null, v);
        db.close();
        refreshNotesPreview(jobCallId);
        return id;
    }

    /**
     * Returns a job call's notes, newest first.
     */
    public List<CallNote> getNotesForJob(long jobCallId) {
        List<CallNote> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_NOTES, null, COLUMN_NOTE_JOB_ID + "=?",
                new String[]{String.valueOf(jobCallId)}, null, null, COLUMN_NOTE_TIME + " DESC");
        if (c != null) {
            while (c.moveToNext()) {
                list.add(new CallNote(
                        c.getLong(c.getColumnIndexOrThrow(COLUMN_NOTE_ID)),
                        c.getLong(c.getColumnIndexOrThrow(COLUMN_NOTE_JOB_ID)),
                        c.getString(c.getColumnIndexOrThrow(COLUMN_NOTE_TEXT)),
                        c.getLong(c.getColumnIndexOrThrow(COLUMN_NOTE_TIME))));
            }
            c.close();
        }
        db.close();
        return list;
    }

    public void deleteNote(long noteId, long jobCallId) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_NOTES, COLUMN_NOTE_ID + "=?", new String[]{String.valueOf(noteId)});
        db.close();
        refreshNotesPreview(jobCallId);
    }

    /**
     * Keeps job_calls.notes set to the latest note text so the tracker card preview
     * (and any older code reading that column) keeps working.
     */
    public void refreshNotesPreview(long jobCallId) {
        SQLiteDatabase db = this.getWritableDatabase();
        String latest = "";
        Cursor c = db.query(TABLE_NOTES, new String[]{COLUMN_NOTE_TEXT}, COLUMN_NOTE_JOB_ID + "=?",
                new String[]{String.valueOf(jobCallId)}, null, null, COLUMN_NOTE_TIME + " DESC", "1");
        if (c != null) {
            if (c.moveToFirst()) {
                latest = c.getString(0);
            }
            c.close();
        }
        ContentValues v = new ContentValues();
        v.put(COLUMN_NOTES, latest);
        db.update(TABLE_NAME, v, COLUMN_ID + "=?", new String[]{String.valueOf(jobCallId)});
        db.close();
    }

    /**
     * Queries database to find a matched job call by comparing phone numbers.
     * Uses PhoneNumberUtils.compare to account for country codes and formatting.
     */
    public JobCall getJobCallByNumber(Context context, String incomingNumber) {
        if (incomingNumber == null || incomingNumber.trim().isEmpty()) {
            return null;
        }

        List<JobCall> allCalls = getAllJobCalls();
        for (JobCall call : allCalls) {
            if (PhoneNumberUtils.compare(context, call.getPhoneNumber(), incomingNumber)) {
                return call;
            }
        }
        return null;
    }
}

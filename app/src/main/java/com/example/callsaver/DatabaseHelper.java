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
    private static final int DATABASE_VERSION = 6; // V6: multiple phones per job entry

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

    // V5: per-entry call history (each in/out/missed call logged against a job call).
    public static final String TABLE_HISTORY = "call_history";
    public static final String COLUMN_HIST_ID = "id";
    public static final String COLUMN_HIST_JOB_ID = "job_call_id";
    public static final String COLUMN_HIST_TYPE = "call_type";
    public static final String COLUMN_HIST_DURATION = "duration";
    public static final String COLUMN_HIST_TIME = "call_time";

    // V6: multiple phone numbers and recruiter names mapped to a single company log.
    public static final String TABLE_PHONES = "job_phones";
    public static final String COLUMN_PHONE_ID = "id";
    public static final String COLUMN_PHONE_JOB_ID = "job_call_id";
    public static final String COLUMN_PHONE_NUMBER = "phone_number";
    public static final String COLUMN_PHONE_RECRUITER_NAME = "recruiter_name";

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
        db.execSQL(createHistoryTableSql());
        db.execSQL(createPhonesTableSql());
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
        if (oldVersion < 5) {
            db.execSQL(createHistoryTableSql());
        }
        if (oldVersion < 6) {
            db.execSQL(createPhonesTableSql());
            migratePhonesData(db);
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

    private static String createHistoryTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_HISTORY + "("
                + COLUMN_HIST_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_HIST_JOB_ID + " INTEGER,"
                + COLUMN_HIST_TYPE + " TEXT,"
                + COLUMN_HIST_DURATION + " INTEGER,"
                + COLUMN_HIST_TIME + " INTEGER"
                + ")";
    }

    private static String createPhonesTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_PHONES + "("
                + COLUMN_PHONE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_PHONE_JOB_ID + " INTEGER,"
                + COLUMN_PHONE_NUMBER + " TEXT,"
                + COLUMN_PHONE_RECRUITER_NAME + " TEXT"
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

    private void migratePhonesData(SQLiteDatabase db) {
        Cursor c = db.query(TABLE_NAME,
                new String[]{COLUMN_ID, COLUMN_PHONE_NUMBER},
                null, null, null, null, null);
        if (c != null) {
            while (c.moveToNext()) {
                int id = c.getInt(0);
                String phone = c.getString(1);
                if (phone != null && !phone.trim().isEmpty()) {
                    ContentValues v = new ContentValues();
                    v.put(COLUMN_PHONE_JOB_ID, id);
                    v.put(COLUMN_PHONE_NUMBER, phone.trim());
                    v.put(COLUMN_PHONE_RECRUITER_NAME, ""); // Default empty name on migration
                    db.insert(TABLE_PHONES, null, v);
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
        if (id != -1) {
            ContentValues pValues = new ContentValues();
            pValues.put(COLUMN_PHONE_JOB_ID, id);
            pValues.put(COLUMN_PHONE_NUMBER, jobCall.getPhoneNumber());
            pValues.put(COLUMN_PHONE_RECRUITER_NAME, jobCall.getRecruiterName());
            db.insert(TABLE_PHONES, null, pValues);
        }
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
     * Logs a call (Incoming / Outgoing / Missed) against a job entry.
     */
    public long insertCallHistory(long jobCallId, String type, int durationSec, long timestamp) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COLUMN_HIST_JOB_ID, jobCallId);
        v.put(COLUMN_HIST_TYPE, type);
        v.put(COLUMN_HIST_DURATION, durationSec);
        v.put(COLUMN_HIST_TIME, timestamp);
        long id = db.insert(TABLE_HISTORY, null, v);
        db.close();
        return id;
    }

    public List<CallHistory> getCallHistoryForJob(long jobCallId) {
        List<CallHistory> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_HISTORY, null, COLUMN_HIST_JOB_ID + "=?",
                new String[]{String.valueOf(jobCallId)}, null, null, COLUMN_HIST_TIME + " DESC");
        if (c != null) {
            while (c.moveToNext()) {
                list.add(new CallHistory(
                        c.getLong(c.getColumnIndexOrThrow(COLUMN_HIST_ID)),
                        c.getLong(c.getColumnIndexOrThrow(COLUMN_HIST_JOB_ID)),
                        c.getString(c.getColumnIndexOrThrow(COLUMN_HIST_TYPE)),
                        c.getInt(c.getColumnIndexOrThrow(COLUMN_HIST_DURATION)),
                        c.getLong(c.getColumnIndexOrThrow(COLUMN_HIST_TIME))));
            }
            c.close();
        }
        db.close();
        return list;
    }

    /**
     * Queries database to find a matched job call by comparing phone numbers.
     * Uses PhoneNumberUtils.compare to account for country codes and formatting.
     */
    public JobCall getJobCallByNumber(Context context, String incomingNumber) {
        if (incomingNumber == null || incomingNumber.trim().isEmpty()) {
            return null;
        }

        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_PHONES, new String[]{COLUMN_PHONE_JOB_ID, COLUMN_PHONE_NUMBER, COLUMN_PHONE_RECRUITER_NAME}, null, null, null, null, null);
        long matchedJobId = -1;
        String recruiterName = "";
        String matchedPhone = "";
        
        if (c != null) {
            while (c.moveToNext()) {
                String dbPhone = c.getString(1);
                if (PhoneNumberUtils.compare(context, dbPhone, incomingNumber)) {
                    matchedJobId = c.getLong(0);
                    recruiterName = c.getString(2);
                    matchedPhone = dbPhone;
                    break;
                }
            }
            c.close();
        }
        
        if (matchedJobId != -1) {
            Cursor cursor = db.query(TABLE_NAME, null, COLUMN_ID + "=?", new String[]{String.valueOf(matchedJobId)}, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    JobCall call = new JobCall(
                            cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                            matchedPhone,
                            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_COMPANY_NAME)),
                            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ROUND_STATUS)),
                            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TAGS)),
                            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOTES)),
                            cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_DURATION)),
                            cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                    );
                    call.setRecruiterName(recruiterName);
                    cursor.close();
                    db.close();
                    return call;
                }
                cursor.close();
            }
        }
        db.close();
        
        // Fallback: check legacy phone number column if table mapping has gaps
        List<JobCall> allCalls = getAllJobCalls();
        for (JobCall call : allCalls) {
            if (PhoneNumberUtils.compare(context, call.getPhoneNumber(), incomingNumber)) {
                return call;
            }
        }
        
        return null;
    }

    public JobCall getJobCallByCompany(String companyName) {
        if (companyName == null || companyName.trim().isEmpty()) {
            return null;
        }
        SQLiteDatabase db = this.getReadableDatabase();
        // Case-insensitive search using UPPER
        String query = "SELECT * FROM " + TABLE_NAME + " WHERE UPPER(TRIM(" + COLUMN_COMPANY_NAME + ")) = UPPER(TRIM(?))";
        Cursor cursor = db.rawQuery(query, new String[]{companyName});
        JobCall call = null;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                call = new JobCall(
                        cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHONE_NUMBER)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_COMPANY_NAME)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ROUND_STATUS)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TAGS)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOTES)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_DURATION)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                );
            }
            cursor.close();
        }
        db.close();
        return call;
    }

    public long linkPhoneToJob(long jobId, String phoneNumber, String recruiterName) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return -1;
        }
        SQLiteDatabase db = this.getWritableDatabase();
        
        // Check if number already linked to this jobId
        Cursor c = db.query(TABLE_PHONES, new String[]{COLUMN_PHONE_ID}, COLUMN_PHONE_JOB_ID + "=? AND " + COLUMN_PHONE_NUMBER + "=?", new String[]{String.valueOf(jobId), phoneNumber.trim()}, null, null, null);
        boolean exists = c != null && c.moveToFirst();
        if (c != null) c.close();
        
        ContentValues v = new ContentValues();
        v.put(COLUMN_PHONE_JOB_ID, jobId);
        v.put(COLUMN_PHONE_NUMBER, phoneNumber.trim());
        v.put(COLUMN_PHONE_RECRUITER_NAME, recruiterName != null ? recruiterName.trim() : "");
        
        long result;
        if (exists) {
            result = db.update(TABLE_PHONES, v, COLUMN_PHONE_JOB_ID + "=? AND " + COLUMN_PHONE_NUMBER + "=?", new String[]{String.valueOf(jobId), phoneNumber.trim()});
        } else {
            result = db.insert(TABLE_PHONES, null, v);
        }
        db.close();
        return result;
    }

    public List<String> getPhonesForJob(long jobId) {
        List<String> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_PHONES, new String[]{COLUMN_PHONE_NUMBER, COLUMN_PHONE_RECRUITER_NAME}, COLUMN_PHONE_JOB_ID + "=?", new String[]{String.valueOf(jobId)}, null, null, null);
        if (c != null) {
            while (c.moveToNext()) {
                String number = c.getString(0);
                String name = c.getString(1);
                String display = number;
                if (name != null && !name.trim().isEmpty()) {
                    display = name.trim() + " (" + number + ")";
                }
                list.add(display);
            }
            c.close();
        }
        db.close();
        return list;
    }
}

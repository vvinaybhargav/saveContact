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
    private static final int DATABASE_VERSION = 12; // V12: add interest_rating

    public static final String TABLE_NAME = "job_calls";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_PHONE_NUMBER = "phone_number";
    public static final String COLUMN_COMPANY_NAME = "company_name";
    public static final String COLUMN_ROUND_STATUS = "round_status";
    public static final String COLUMN_TAGS = "tags";
    public static final String COLUMN_NOTES = "notes";
    public static final String COLUMN_DURATION = "duration"; // Added in V3
    public static final String COLUMN_TIMESTAMP = "timestamp";

    // V7 columns
    public static final String COLUMN_CANDIDATE_NAME = "candidate_name";
    public static final String COLUMN_APPLIED_ROLE = "applied_role";
    public static final String COLUMN_TENTATIVE_SCHEDULE = "tentative_schedule";
    public static final String COLUMN_NOTICE_PERIOD = "notice_period";
    public static final String COLUMN_MAIN_AGENDA = "main_agenda";
    public static final String COLUMN_KEY_DISCUSSION_POINTS = "key_discussion_points";
    public static final String COLUMN_NEXT_STEPS = "next_steps";

    // V10: comma-separated skills mentioned on calls that matched / didn't match the
    // user's stated interests (Settings > My Interests).
    public static final String COLUMN_MATCHING_SKILLS = "matching_skills";
    public static final String COLUMN_NOT_MATCHING_SKILLS = "not_matching_skills";

    // V11 columns
    public static final String COLUMN_JD_LINK = "jd_link";
    public static final String COLUMN_JD_IMAGE_PATH = "jd_image_path";

    // V12 columns
    public static final String COLUMN_INTEREST_RATING = "interest_rating";

    // V4: individual timestamped notes, one row per note, linked to a job call.
    public static final String TABLE_NOTES = "call_notes";
    public static final String COLUMN_NOTE_ID = "id";
    public static final String COLUMN_NOTE_JOB_ID = "job_call_id";
    public static final String COLUMN_NOTE_TEXT = "note_text";
    public static final String COLUMN_NOTE_TIME = "note_time";
    // V8: where the note came from — "call" (auto-detected/background) or "manual"
    // (user manually uploaded & transcribed a recording from the edit-log screen).
    public static final String COLUMN_NOTE_SOURCE = "note_source";
    public static final String NOTE_SOURCE_CALL = "call";
    public static final String NOTE_SOURCE_MANUAL = "manual";

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
                + COLUMN_TIMESTAMP + " INTEGER,"
                + COLUMN_CANDIDATE_NAME + " TEXT,"
                + COLUMN_APPLIED_ROLE + " TEXT,"
                + COLUMN_TENTATIVE_SCHEDULE + " TEXT,"
                + COLUMN_NOTICE_PERIOD + " TEXT,"
                + COLUMN_MAIN_AGENDA + " TEXT,"
                + COLUMN_KEY_DISCUSSION_POINTS + " TEXT,"
                + COLUMN_NEXT_STEPS + " TEXT,"
                + COLUMN_MATCHING_SKILLS + " TEXT,"
                + COLUMN_NOT_MATCHING_SKILLS + " TEXT,"
                + COLUMN_JD_LINK + " TEXT,"
                + COLUMN_JD_IMAGE_PATH + " TEXT,"
                + COLUMN_INTEREST_RATING + " TEXT"
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
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_CANDIDATE_NAME + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_APPLIED_ROLE + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_TENTATIVE_SCHEDULE + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NOTICE_PERIOD + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_MAIN_AGENDA + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_KEY_DISCUSSION_POINTS + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NEXT_STEPS + " TEXT");
        }
        if (oldVersion >= 4 && oldVersion < 8) {
            // Only needed if call_notes already existed without this column; a device
            // jumping straight from <4 to 8 gets it for free via createNotesTableSql().
            db.execSQL("ALTER TABLE " + TABLE_NOTES + " ADD COLUMN " + COLUMN_NOTE_SOURCE
                    + " TEXT DEFAULT '" + NOTE_SOURCE_CALL + "'");
        }
        if (oldVersion < 9) {
            // The "Rejected" round status was renamed to "Negative"; migrate existing rows.
            ContentValues v = new ContentValues();
            v.put(COLUMN_ROUND_STATUS, "Negative");
            db.update(TABLE_NAME, v, COLUMN_ROUND_STATUS + "=?", new String[]{"Rejected"});
        }
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_MATCHING_SKILLS + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NOT_MATCHING_SKILLS + " TEXT");
        }
        if (oldVersion < 11) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_JD_LINK + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_JD_IMAGE_PATH + " TEXT");
        }
        if (oldVersion < 12) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_INTEREST_RATING + " TEXT");
        }
    }

    private static String createNotesTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_NOTES + "("
                + COLUMN_NOTE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_NOTE_JOB_ID + " INTEGER,"
                + COLUMN_NOTE_TEXT + " TEXT,"
                + COLUMN_NOTE_TIME + " INTEGER,"
                + COLUMN_NOTE_SOURCE + " TEXT DEFAULT '" + NOTE_SOURCE_CALL + "'"
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
        
        values.put(COLUMN_CANDIDATE_NAME, jobCall.getCandidateName());
        values.put(COLUMN_APPLIED_ROLE, jobCall.getAppliedRole());
        values.put(COLUMN_TENTATIVE_SCHEDULE, jobCall.getTentativeSchedule());
        values.put(COLUMN_NOTICE_PERIOD, jobCall.getNoticePeriod());
        values.put(COLUMN_MAIN_AGENDA, jobCall.getMainAgenda());
        values.put(COLUMN_KEY_DISCUSSION_POINTS, jobCall.getKeyDiscussionPoints());
        values.put(COLUMN_NEXT_STEPS, jobCall.getNextSteps());
        values.put(COLUMN_MATCHING_SKILLS, jobCall.getMatchingSkills());
        values.put(COLUMN_NOT_MATCHING_SKILLS, jobCall.getNotMatchingSkills());
        values.put(COLUMN_JD_LINK, jobCall.getJdLink());
        values.put(COLUMN_JD_IMAGE_PATH, jobCall.getJdImagePath());
        values.put(COLUMN_INTEREST_RATING, jobCall.getInterestRating());

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
                call.setCandidateName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CANDIDATE_NAME)));
                call.setAppliedRole(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_APPLIED_ROLE)));
                call.setTentativeSchedule(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TENTATIVE_SCHEDULE)));
                call.setNoticePeriod(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOTICE_PERIOD)));
                call.setMainAgenda(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MAIN_AGENDA)));
                call.setKeyDiscussionPoints(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_KEY_DISCUSSION_POINTS)));
                call.setNextSteps(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NEXT_STEPS)));
                call.setMatchingSkills(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MATCHING_SKILLS)));
                call.setNotMatchingSkills(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOT_MATCHING_SKILLS)));
                call.setJdLink(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JD_LINK)));
                call.setJdImagePath(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JD_IMAGE_PATH)));
                call.setInterestRating(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_INTEREST_RATING)));
                callsList.add(call);
            } while (cursor.moveToNext());
        }

        cursor.close();
        db.close();
        return callsList;
    }

    /**
     * Fetches all job call logs ordered by most recent activity (latest call-history
     * entry, latest note, or the entry's own timestamp - whichever is newest), not by
     * when the entry was first created. Used by the Tracker list.
     */
    public List<JobCall> getAllJobCallsSortedByRecentActivity() {
        List<JobCall> callsList = new ArrayList<>();
        String selectQuery = "SELECT jc.*, "
                + "MAX(jc." + COLUMN_TIMESTAMP + ", "
                + "COALESCE((SELECT MAX(" + COLUMN_HIST_TIME + ") FROM " + TABLE_HISTORY
                + " WHERE " + COLUMN_HIST_JOB_ID + "=jc." + COLUMN_ID + "), 0), "
                + "COALESCE((SELECT MAX(" + COLUMN_NOTE_TIME + ") FROM " + TABLE_NOTES
                + " WHERE " + COLUMN_NOTE_JOB_ID + "=jc." + COLUMN_ID + "), 0)) AS last_activity "
                + "FROM " + TABLE_NAME + " jc ORDER BY last_activity DESC";

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
                call.setCandidateName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CANDIDATE_NAME)));
                call.setAppliedRole(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_APPLIED_ROLE)));
                call.setTentativeSchedule(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TENTATIVE_SCHEDULE)));
                call.setNoticePeriod(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOTICE_PERIOD)));
                call.setMainAgenda(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MAIN_AGENDA)));
                call.setKeyDiscussionPoints(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_KEY_DISCUSSION_POINTS)));
                call.setNextSteps(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NEXT_STEPS)));
                call.setMatchingSkills(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MATCHING_SKILLS)));
                call.setNotMatchingSkills(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOT_MATCHING_SKILLS)));
                call.setJdLink(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JD_LINK)));
                call.setJdImagePath(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JD_IMAGE_PATH)));
                call.setInterestRating(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_INTEREST_RATING)));
                callsList.add(call);
            } while (cursor.moveToNext());
        }

        cursor.close();
        db.close();
        return callsList;
    }

    /**
     * Returns the first-ever logged timestamp for a job (its creation time - "first call"),
     * and the most recent call-history/note activity time ("recent call"), for display.
     * Returns {firstCallMillis, mostRecentCallMillis}; mostRecentCallMillis is 0 if there
     * has been no call-history/note activity beyond the initial log.
     */
    public long[] getFirstAndRecentCallTimes(long jobId) {
        long firstCall = 0;
        long recentCall = 0;
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.query(TABLE_NAME, new String[]{COLUMN_TIMESTAMP}, COLUMN_ID + "=?",
                new String[]{String.valueOf(jobId)}, null, null, null);
        if (c != null) {
            if (c.moveToFirst()) firstCall = c.getLong(0);
            c.close();
        }
        Cursor c2 = db.rawQuery(
                "SELECT MAX(t) FROM (SELECT MAX(" + COLUMN_HIST_TIME + ") AS t FROM " + TABLE_HISTORY
                        + " WHERE " + COLUMN_HIST_JOB_ID + "=? UNION SELECT MAX(" + COLUMN_NOTE_TIME + ") FROM "
                        + TABLE_NOTES + " WHERE " + COLUMN_NOTE_JOB_ID + "=?)",
                new String[]{String.valueOf(jobId), String.valueOf(jobId)});
        if (c2 != null) {
            if (c2.moveToFirst() && !c2.isNull(0)) recentCall = c2.getLong(0);
            c2.close();
        }
        db.close();
        // "Recent call" only makes sense if there was activity AFTER the first log.
        if (recentCall <= firstCall) recentCall = 0;
        return new long[]{firstCall, recentCall};
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
        
        values.put(COLUMN_CANDIDATE_NAME, jobCall.getCandidateName());
        values.put(COLUMN_APPLIED_ROLE, jobCall.getAppliedRole());
        values.put(COLUMN_TENTATIVE_SCHEDULE, jobCall.getTentativeSchedule());
        values.put(COLUMN_NOTICE_PERIOD, jobCall.getNoticePeriod());
        values.put(COLUMN_MAIN_AGENDA, jobCall.getMainAgenda());
        values.put(COLUMN_KEY_DISCUSSION_POINTS, jobCall.getKeyDiscussionPoints());
        values.put(COLUMN_NEXT_STEPS, jobCall.getNextSteps());
        values.put(COLUMN_MATCHING_SKILLS, jobCall.getMatchingSkills());
        values.put(COLUMN_NOT_MATCHING_SKILLS, jobCall.getNotMatchingSkills());
        values.put(COLUMN_JD_LINK, jobCall.getJdLink());
        values.put(COLUMN_JD_IMAGE_PATH, jobCall.getJdImagePath());
        values.put(COLUMN_INTEREST_RATING, jobCall.getInterestRating());

        int count = db.update(TABLE_NAME, values, COLUMN_ID + " = ?",
                new String[]{String.valueOf(jobCall.getId())});
        db.close();
        return count;
    }

    public void updateRoundStatus(long jobId, String newStatus) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COLUMN_ROUND_STATUS, newStatus);
        db.update(TABLE_NAME, v, COLUMN_ID + "=?", new String[]{String.valueOf(jobId)});
        db.close();
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
     * Merges "loser" into "keep": moves its notes, call history and linked phone
     * numbers onto the kept entry, fills any blank fields on "keep" from "loser"
     * (never overwrites a value that's already set), then deletes "loser".
     * Used by the duplicate-company suggestion flow to consolidate two separate
     * rows that turned out to be the same recruiter/company.
     */
    public void mergeJobCalls(long keepId, long loserId) {
        if (keepId == loserId) return;
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            db.beginTransaction();

            ContentValues moveNotes = new ContentValues();
            moveNotes.put(COLUMN_NOTE_JOB_ID, keepId);
            db.update(TABLE_NOTES, moveNotes, COLUMN_NOTE_JOB_ID + "=?", new String[]{String.valueOf(loserId)});

            ContentValues moveHistory = new ContentValues();
            moveHistory.put(COLUMN_HIST_JOB_ID, keepId);
            db.update(TABLE_HISTORY, moveHistory, COLUMN_HIST_JOB_ID + "=?", new String[]{String.valueOf(loserId)});

            ContentValues movePhones = new ContentValues();
            movePhones.put(COLUMN_PHONE_JOB_ID, keepId);
            db.update(TABLE_PHONES, movePhones, COLUMN_PHONE_JOB_ID + "=?", new String[]{String.valueOf(loserId)});

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }

        // Fill any blanks on the kept entry from the loser (outside the transaction,
        // reusing the normal getter/setter + updateJobCall path for consistency).
        JobCall keep = getJobCallById(keepId);
        JobCall loser = getJobCallById(loserId);
        if (keep != null && loser != null) {
            // recruiterName lives per-phone in job_phones (already moved above), not on
            // job_calls, so there's nothing to merge for it here.
            if (isBlank(keep.getCompanyName())) keep.setCompanyName(loser.getCompanyName());
            if (isBlank(keep.getCandidateName())) keep.setCandidateName(loser.getCandidateName());
            if (isBlank(keep.getAppliedRole())) keep.setAppliedRole(loser.getAppliedRole());
            if (isBlank(keep.getTags())) keep.setTags(loser.getTags());
            if (isBlank(keep.getTentativeSchedule())) keep.setTentativeSchedule(loser.getTentativeSchedule());
            if (isBlank(keep.getNoticePeriod())) keep.setNoticePeriod(loser.getNoticePeriod());
            if (isBlank(keep.getMainAgenda())) keep.setMainAgenda(loser.getMainAgenda());
            if (isBlank(keep.getNextSteps())) keep.setNextSteps(loser.getNextSteps());
            updateJobCall(keep);
        }

        deleteJobCall((int) loserId);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Fetches a single job call by id, or null if it doesn't exist.
     */
    public JobCall getJobCallById(long id) {
        for (JobCall call : getAllJobCalls()) {
            if (call.getId() == id) return call;
        }
        return null;
    }

    /**
     * Adds a timestamped note to a job call's timeline and refreshes the card preview.
     * Defaults to source "call" (auto-detected/background origin).
     */
    public long insertNote(long jobCallId, String note, long timestamp) {
        return insertNote(jobCallId, note, timestamp, NOTE_SOURCE_CALL);
    }

    /**
     * Adds a timestamped note tagged with its origin: NOTE_SOURCE_CALL for a real
     * call, or NOTE_SOURCE_MANUAL when the user manually uploaded/transcribed a
     * recording from the edit-log screen. The timeline numbers these separately
     * ("Call N" vs "MCall N").
     */
    public long insertNote(long jobCallId, String note, long timestamp, String source) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COLUMN_NOTE_JOB_ID, jobCallId);
        v.put(COLUMN_NOTE_TEXT, note);
        v.put(COLUMN_NOTE_TIME, timestamp);
        v.put(COLUMN_NOTE_SOURCE, source == null ? NOTE_SOURCE_CALL : source);
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
                int sourceIdx = c.getColumnIndex(COLUMN_NOTE_SOURCE);
                String source = sourceIdx >= 0 ? c.getString(sourceIdx) : null;
                list.add(new CallNote(
                        c.getLong(c.getColumnIndexOrThrow(COLUMN_NOTE_ID)),
                        c.getLong(c.getColumnIndexOrThrow(COLUMN_NOTE_JOB_ID)),
                        c.getString(c.getColumnIndexOrThrow(COLUMN_NOTE_TEXT)),
                        c.getLong(c.getColumnIndexOrThrow(COLUMN_NOTE_TIME)),
                        source == null ? NOTE_SOURCE_CALL : source));
            }
            c.close();
        }
        db.close();
        return list;
    }

    /** Replaces a note's text in place (e.g. rewriting a raw hand-typed note into an AI-cleaned version), keeping its id/timestamp/position. */
    public void updateNoteText(long noteId, long jobCallId, String newText) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COLUMN_NOTE_TEXT, newText);
        db.update(TABLE_NOTES, v, COLUMN_NOTE_ID + "=?", new String[]{String.valueOf(noteId)});
        db.close();
        refreshNotesPreview(jobCallId);
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
                    call.setCandidateName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CANDIDATE_NAME)));
                    call.setAppliedRole(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_APPLIED_ROLE)));
                    call.setTentativeSchedule(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TENTATIVE_SCHEDULE)));
                    call.setNoticePeriod(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOTICE_PERIOD)));
                    call.setMainAgenda(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MAIN_AGENDA)));
                    call.setKeyDiscussionPoints(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_KEY_DISCUSSION_POINTS)));
                    call.setNextSteps(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NEXT_STEPS)));
                call.setMatchingSkills(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MATCHING_SKILLS)));
                call.setNotMatchingSkills(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOT_MATCHING_SKILLS)));
                call.setJdLink(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JD_LINK)));
                call.setJdImagePath(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JD_IMAGE_PATH)));
                call.setInterestRating(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_INTEREST_RATING)));
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
                call.setCandidateName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CANDIDATE_NAME)));
                call.setAppliedRole(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_APPLIED_ROLE)));
                call.setTentativeSchedule(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TENTATIVE_SCHEDULE)));
                call.setNoticePeriod(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOTICE_PERIOD)));
                call.setMainAgenda(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MAIN_AGENDA)));
                call.setKeyDiscussionPoints(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_KEY_DISCUSSION_POINTS)));
                call.setNextSteps(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NEXT_STEPS)));
                call.setMatchingSkills(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MATCHING_SKILLS)));
                call.setNotMatchingSkills(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOT_MATCHING_SKILLS)));
                call.setJdLink(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JD_LINK)));
                call.setJdImagePath(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_JD_IMAGE_PATH)));
                call.setInterestRating(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_INTEREST_RATING)));
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

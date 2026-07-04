package com.example.callsaver;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "JobTracker.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_NAME = "job_calls";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_PHONE_NUMBER = "phone_number";
    public static final String COLUMN_COMPANY_NAME = "company_name";
    public static final String COLUMN_ROUND_STATUS = "round_status";
    public static final String COLUMN_TAGS = "tags";
    public static final String COLUMN_TIMESTAMP = "timestamp";

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
                + COLUMN_TIMESTAMP + " INTEGER"
                + ")";
        db.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
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
     * Updates an existing job call entry (status, company name, tags, etc.).
     */
    public int updateJobCall(JobCall jobCall) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_PHONE_NUMBER, jobCall.getPhoneNumber());
        values.put(COLUMN_COMPANY_NAME, jobCall.getCompanyName());
        values.put(COLUMN_ROUND_STATUS, jobCall.getRoundStatus());
        values.put(COLUMN_TAGS, jobCall.getTags());
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
}

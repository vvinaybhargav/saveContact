package com.example.callsaver;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;

public class CallRecordingScanner {

    private static final String TAG = "CallRecordingScanner";

    /**
     * Scans call recording directories for a file matching the phone number,
     * created around the call end time.
     */
    public static File findLatestCallRecording(Context context, String phoneNumber, long callEndTime) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return null;
        }

        // Clean phone number (extract digits only) to match against filenames
        final String cleanNumber = phoneNumber.replaceAll("[^\\d]", "");
        if (cleanNumber.isEmpty()) {
            return null;
        }

        // Potential call recording directories on OnePlus / Oppo (ODialer) and standard Android
        File[] candidateDirs = new File[] {
                new File(Environment.getExternalStorageDirectory(), "Music/Recordings/Call Recordings"),
                new File(Environment.getExternalStorageDirectory(), "Recordings/Call"),
                new File(Environment.getExternalStorageDirectory(), "Recordings/Call Recordings"),
                new File(Environment.getExternalStorageDirectory(), "Recordings"),
                new File(Environment.getExternalStorageDirectory(), "Music/Recordings")
        };

        File bestFile = null;
        long closestDiff = Long.MAX_VALUE;

        // Allow matching files up to 5 minutes before/after the call finished
        long timeWindowMs = 5 * 60 * 1000L;

        for (File dir : candidateDirs) {
            if (!dir.exists() || !dir.isDirectory()) {
                continue;
            }

            Log.d(TAG, "Scanning directory: " + dir.getAbsolutePath());
            File[] files = dir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File file) {
                    if (!file.isFile()) return false;
                    String name = file.getName().toLowerCase();
                    // Match audio extensions
                    boolean isAudio = name.endsWith(".mp3") || name.endsWith(".wav") || 
                                      name.endsWith(".m4a") || name.endsWith(".amr") || 
                                      name.endsWith(".aac") || name.endsWith(".ogg");
                    if (!isAudio) return false;

                    // Match if filename contains clean phone number digits
                    String cleanName = name.replaceAll("[^\\d]", "");
                    return cleanName.contains(cleanNumber) || name.contains(cleanNumber);
                }
            });

            if (files != null) {
                for (File file : files) {
                    long modTime = file.lastModified();
                    long diff = Math.abs(modTime - callEndTime);
                    if (diff < timeWindowMs && diff < closestDiff) {
                        closestDiff = diff;
                        bestFile = file;
                    }
                }
            }
        }

        if (bestFile != null) {
            Log.d(TAG, "Found matching call recording: " + bestFile.getAbsolutePath() + " (Time diff: " + closestDiff / 1000 + "s)");
        } else {
            Log.d(TAG, "No matching call recording found for " + phoneNumber);
        }

        return bestFile;
    }
}

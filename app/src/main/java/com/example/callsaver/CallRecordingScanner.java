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
            DebugLogger.log(context, "[Scanner] Phone number is empty.");
            return null;
        }

        final String cleanNumber = phoneNumber.replaceAll("[^\\d]", "");
        if (cleanNumber.isEmpty()) {
            DebugLogger.log(context, "[Scanner] Cleaned digits from " + phoneNumber + " are empty.");
            return null;
        }

        File[] candidateDirs = new File[] {
                new File(Environment.getExternalStorageDirectory(), "Music/Recordings/Call Recordings"),
                new File(Environment.getExternalStorageDirectory(), "Recordings/Call"),
                new File(Environment.getExternalStorageDirectory(), "Recordings/Call Recordings"),
                new File(Environment.getExternalStorageDirectory(), "Recordings"),
                new File(Environment.getExternalStorageDirectory(), "Music/Recordings")
        };

        File bestFile = null;
        long closestDiff = Long.MAX_VALUE;
        long timeWindowMs = 5 * 60 * 1000L;

        DebugLogger.log(context, "[Scanner] Checking for recordings matching number: " + cleanNumber + ", endTime timestamp=" + callEndTime);

        for (File dir : candidateDirs) {
            DebugLogger.log(context, "[Scanner] Checking directory: " + dir.getAbsolutePath() + " (Exists: " + dir.exists() + ", IsDirectory: " + dir.isDirectory() + ")");
            if (!dir.exists() || !dir.isDirectory()) {
                continue;
            }

            File[] allFiles = dir.listFiles();
            if (allFiles != null) {
                DebugLogger.log(context, "[Scanner] Directory contains " + allFiles.length + " files total.");
                for (File f : allFiles) {
                    if (f.isFile()) {
                        String name = f.getName().toLowerCase();
                        boolean isAudio = name.endsWith(".mp3") || name.endsWith(".wav") || 
                                          name.endsWith(".m4a") || name.endsWith(".amr") || 
                                          name.endsWith(".aac") || name.endsWith(".ogg") ||
                                          name.endsWith(".mp4");
                        
                        String cleanName = name.replaceAll("[^\\d]", "");
                        boolean matchesNumber = cleanName.contains(cleanNumber) || name.contains(cleanNumber);
                        
                        long modTime = f.lastModified();
                        long diff = Math.abs(modTime - callEndTime);
                        
                        DebugLogger.log(context, "  - File: " + f.getName() + " (isAudio: " + isAudio + ", matchesNumber: " + matchesNumber + ", timeDiff: " + (diff / 1000) + "s)");

                        if (isAudio && matchesNumber && (callEndTime == 0 || diff < timeWindowMs) && diff < closestDiff) {
                            closestDiff = diff;
                            bestFile = f;
                        }
                    }
                }
            } else {
                DebugLogger.log(context, "[Scanner] listFiles() returned null for: " + dir.getAbsolutePath());
            }
        }

        if (bestFile != null) {
            DebugLogger.log(context, "[Scanner] Success! Best match found: " + bestFile.getAbsolutePath() + " (Time diff: " + (closestDiff / 1000) + "s)");
        } else {
            DebugLogger.log(context, "[Scanner] Failure: No matching file located in directories.");
        }

        return bestFile;
    }
}

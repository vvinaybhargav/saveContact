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

        File[] candidateDirs = buildCandidateDirs(context);

        File bestFile = null;
        long closestDiff = Long.MAX_VALUE;
        long timeWindowMs = 5 * 60 * 1000L;

        // Fallback: track the newest audio file created within 60 seconds (1 minute) of the call end
        File newestAudioFile = null;
        long newestAudioDiff = Long.MAX_VALUE;
        long fallbackWindowMs = 60 * 1000L;

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

                        // Exact match (by phone number)
                        if (isAudio && matchesNumber && (callEndTime == 0 || diff < timeWindowMs) && diff < closestDiff) {
                            closestDiff = diff;
                            bestFile = f;
                        }

                        // Fallback match (newest file created around call end)
                        if (isAudio && callEndTime > 0 && diff < fallbackWindowMs && diff < newestAudioDiff) {
                            newestAudioDiff = diff;
                            newestAudioFile = f;
                        }
                    }
                }
            } else {
                DebugLogger.log(context, "[Scanner] listFiles() returned null for: " + dir.getAbsolutePath());
            }
        }

        // Apply fallback if no exact phone number match was found
        if (bestFile == null && newestAudioFile != null) {
            DebugLogger.log(context, "[Scanner] No exact phone number match. Falling back to newest audio file created during call: " 
                    + newestAudioFile.getName() + " (Time diff: " + (newestAudioDiff / 1000) + "s)");
            bestFile = newestAudioFile;
            closestDiff = newestAudioDiff;
        }

        if (bestFile != null) {
            DebugLogger.log(context, "[Scanner] Success! Best match found: " + bestFile.getAbsolutePath() + " (Time diff: " + (closestDiff / 1000) + "s)");
        } else {
            DebugLogger.log(context, "[Scanner] Failure: No matching file located in directories.");
        }

        return bestFile;
    }

    /** Same folder list as the auto-scanner, exposed for the manual "browse recordings" pickers. */
    public static File[] getCandidateDirsForBrowsing(Context context) {
        return buildCandidateDirs(context);
    }

    /**
     * Builds the list of folders to scan, with the user's custom folder (Settings >
     * Call Recording Folder), if set, checked first so it takes priority over the
     * built-in defaults.
     */
    private static File[] buildCandidateDirs(Context context) {
        java.util.List<File> dirs = new java.util.ArrayList<>();

        String customPath = context.getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE)
                .getString("custom_recording_folder", "").trim();
        if (!customPath.isEmpty()) {
            File customDir = customPath.startsWith("/")
                    ? new File(customPath)
                    : new File(Environment.getExternalStorageDirectory(), customPath);
            dirs.add(customDir);
        }

        dirs.add(new File(Environment.getExternalStorageDirectory(), "Music/Recordings/Call Recordings"));
        dirs.add(new File(Environment.getExternalStorageDirectory(), "Recordings/Call"));
        dirs.add(new File(Environment.getExternalStorageDirectory(), "Recordings/Call Recordings"));
        dirs.add(new File(Environment.getExternalStorageDirectory(), "Recordings"));
        dirs.add(new File(Environment.getExternalStorageDirectory(), "Music/Recordings"));

        return dirs.toArray(new File[0]);
    }
}

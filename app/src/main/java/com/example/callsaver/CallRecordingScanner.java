package com.example.callsaver;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CallRecordingScanner {

    private static final String[] SEARCH_PATHS = new String[]{
            Environment.getExternalStorageDirectory() + "/Recordings/Call",
            Environment.getExternalStorageDirectory() + "/Recordings/CallRecord",
            Environment.getExternalStorageDirectory() + "/Recordings/Call Recordings",
            Environment.getExternalStorageDirectory() + "/CallRecordings",
            Environment.getExternalStorageDirectory() + "/Call Record",
            Environment.getExternalStorageDirectory() + "/Sounds/Call",
            Environment.getExternalStorageDirectory() + "/Sounds/CallRecord",
            Environment.getExternalStorageDirectory() + "/Music/Recordings",
            Environment.getExternalStorageDirectory() + "/Music/Recordings/Call Recordings",
            Environment.getExternalStorageDirectory() + "/Music/CallRecord",
            Environment.getExternalStorageDirectory() + "/Music/Call",
            // ColorOS/OPPO/Realme
            Environment.getExternalStorageDirectory() + "/Recordings/Call recordings",
            Environment.getExternalStorageDirectory() + "/PhoneRecord",
            Environment.getExternalStorageDirectory() + "/CallRecord",
            Environment.getExternalStorageDirectory() + "/MIUI/sound_recorder/call_rec",
            Environment.getExternalStorageDirectory() + "/VoiceRecorder"
    };

    // Common parent folders to recurse one level into, so naming variations not
    // explicitly listed above (e.g. a differently-cased or spaced subfolder under
    // Music/Recordings) still get picked up.
    private static final String[] PARENT_DIRS = new String[]{
            Environment.getExternalStorageDirectory() + "/Music",
            Environment.getExternalStorageDirectory() + "/Music/Recordings",
            Environment.getExternalStorageDirectory() + "/Recordings",
            Environment.getExternalStorageDirectory() + "/Sounds"
    };

    /**
     * All call-recording files found in the known Call Recordings folders (incl.
     * "Music/Recordings/Call Recordings"), sorted newest-first by last-modified time -
     * used by the manual "attach recording" picker so it only shows actual call
     * recordings instead of the whole device music library.
     */
    public static List<File> listAllCallRecordings(Context context) {
        List<File> candidateFiles = new ArrayList<>();
        for (String path : SEARCH_PATHS) {
            scanDirectory(new File(path), Long.MIN_VALUE, candidateFiles);
        }
        for (String parentPath : PARENT_DIRS) {
            try {
                File parent = new File(parentPath);
                File[] subDirs = parent.listFiles();
                if (subDirs != null) {
                    for (File subDir : subDirs) {
                        if (subDir.isDirectory() && subDir.getName().toLowerCase().contains("call")) {
                            scanDirectory(subDir, Long.MIN_VALUE, candidateFiles);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // De-dupe (a folder can be matched by both SEARCH_PATHS and the PARENT_DIRS recursion)
        List<File> deduped = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        for (File f : candidateFiles) {
            if (seenPaths.add(f.getAbsolutePath())) deduped.add(f);
        }

        Collections.sort(deduped, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        return deduped;
    }

    public static File findLatestCallRecording(Context context) {
        List<File> candidateFiles = new ArrayList<>();
        long maxAgeMs = System.currentTimeMillis() - (15 * 60 * 1000); // Created in last 15 minutes

        for (String path : SEARCH_PATHS) {
            scanDirectory(new File(path), maxAgeMs, candidateFiles);
        }

        for (String parentPath : PARENT_DIRS) {
            try {
                File parent = new File(parentPath);
                File[] subDirs = parent.listFiles();
                if (subDirs != null) {
                    for (File subDir : subDirs) {
                        if (subDir.isDirectory() && subDir.getName().toLowerCase().contains("call")) {
                            scanDirectory(subDir, maxAgeMs, candidateFiles);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Also query Android MediaStore.Audio for newly created audio files
        try {
            android.database.Cursor cursor = context.getContentResolver().query(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.Audio.Media.DATA, android.provider.MediaStore.Audio.Media.DATE_ADDED},
                    android.provider.MediaStore.Audio.Media.DATE_ADDED + " >= ?",
                    new String[]{String.valueOf((maxAgeMs / 1000L))},
                    android.provider.MediaStore.Audio.Media.DATE_ADDED + " DESC"
            );
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String filePath = cursor.getString(0);
                    if (filePath != null) {
                        File f = new File(filePath);
                        if (f.exists() && f.isFile()) {
                            candidateFiles.add(f);
                        }
                    }
                }
                cursor.close();
            }
        } catch (Exception ignored) {}

        if (candidateFiles.isEmpty()) return null;

        // Sort by newest first
        Collections.sort(candidateFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        return candidateFiles.get(0);
    }

    private static void scanDirectory(File dir, long maxAgeMs, List<File> candidateFiles) {
        try {
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && f.lastModified() >= maxAgeMs) {
                            String name = f.getName().toLowerCase();
                            if (name.endsWith(".m4a") || name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".3gp") || name.endsWith(".aac") || name.endsWith(".amr")) {
                                candidateFiles.add(f);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}

package com.example.callsaver;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DebugLogger {

    private static final String FILE_NAME = "debug_log.txt";

    public static synchronized void log(Context context, String message) {
        try {
            File logFile = new File(context.getExternalFilesDir(null), FILE_NAME);
            FileWriter writer = new FileWriter(logFile, true);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            writer.write(timestamp + " - " + message + "\n");
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized String readLogs(Context context) {
        File logFile = new File(context.getExternalFilesDir(null), FILE_NAME);
        if (!logFile.exists()) {
            return "No logs generated yet.";
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            return "Error reading logs: " + e.getMessage();
        }
        return sb.toString();
    }

    public static synchronized void clearLogs(Context context) {
        File logFile = new File(context.getExternalFilesDir(null), FILE_NAME);
        if (logFile.exists()) {
            logFile.delete();
        }
    }
}

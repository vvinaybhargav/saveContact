package com.example.callsaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Best-effort call recorder. Android blocks the real call-audio stream for
 * third-party apps, so this records via the mic path (VOICE_RECOGNITION, then MIC
 * as fallback). On many OnePlus/OxygenOS builds this captures both sides; on others
 * it may capture mostly your own voice. Runs as a foreground service (microphone
 * type) so recording continues if the call screen is minimized.
 */
public class CallRecorderService extends Service {

    public static final String ACTION_START = "com.example.callsaver.REC_START";
    public static final String ACTION_STOP = "com.example.callsaver.REC_STOP";
    public static final String EXTRA_NAME = "rec_name";

    private static final String CHANNEL_ID = "call_recording_channel";
    private static final int NOTIF_ID = 55;

    private static boolean sRecording = false;
    private MediaRecorder recorder;
    private File outputFile;

    public static boolean isRecording() {
        return sRecording;
    }

    /** Where recordings are stored. */
    public static File recordingsDir(Context context) {
        File dir = new File(context.getExternalFilesDir(null), "recordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundNotification();
        String name = intent != null ? intent.getStringExtra(EXTRA_NAME) : null;
        if (!startRecording(name)) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Call recording", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while a call is being recorded.");
            nm.createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Recording call")
                .setContentText("Tap End in the call to stop.")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean startRecording(String name) {
        File dir = recordingsDir(this);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String safe = (name == null || name.trim().isEmpty()) ? "Call" : name.replaceAll("[^a-zA-Z0-9]", "_");
        outputFile = new File(dir, safe + "_" + stamp + ".m4a");

        if (tryStart(MediaRecorder.AudioSource.VOICE_RECOGNITION)) return true;
        if (tryStart(MediaRecorder.AudioSource.MIC)) return true;
        sRecording = false;
        return false;
    }

    private boolean tryStart(int source) {
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(source);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(outputFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            sRecording = true;
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            releaseRecorder();
            return false;
        }
    }

    private void stopRecording() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception e) {
                // stop() throws if nothing was recorded; delete the empty file.
                if (outputFile != null && outputFile.exists() && outputFile.length() == 0) {
                    outputFile.delete();
                }
            }
            releaseRecorder();
        }
        sRecording = false;
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecording();
    }
}

package com.example.callsaver;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

public class Transcriber {

    public interface TranscriptionCallback {
        void onSuccess(String text);
        void onError(String error);
    }

    private static final OkHttpClient client = new OkHttpClient();

    public static void transcribeCallRecording(Context context, File audioFile, TranscriptionCallback callback) {
        String apiKey = context.getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE)
                .getString("deepgram_api_key", "").trim();

        if (apiKey.isEmpty()) {
            callback.onError("Deepgram API Key is missing. Please save your Deepgram API Key first.");
            return;
        }

        if (!audioFile.exists()) {
            callback.onError("Audio recording file does not exist.");
            return;
        }

        if (audioFile.length() == 0) {
            callback.onError("Recording file is empty (0 bytes) — nothing was recorded.");
            return;
        }

        // Determine mime type from extension (Deepgram also auto-detects from bytes)
        String name = audioFile.getName().toLowerCase();
        String mimeType = "audio/mpeg";
        if (name.endsWith(".wav")) mimeType = "audio/wav";
        else if (name.endsWith(".m4a") || name.endsWith(".mp4")) mimeType = "audio/mp4";
        else if (name.endsWith(".amr")) mimeType = "audio/amr";
        else if (name.endsWith(".aac")) mimeType = "audio/aac";
        else if (name.endsWith(".ogg")) mimeType = "audio/ogg";

        RequestBody requestBody = RequestBody.create(audioFile, MediaType.parse(mimeType));

        // Use Deepgram Nova-2 model with formatting for best results
        Request request = new Request.Builder()
                .url("https://api.deepgram.com/v1/listen?model=nova-2&smart_format=true")
                .header("Authorization", "Token " + apiKey)
                .header("Content-Type", mimeType)
                .post(requestBody)
                .build();

        Handler mainHandler = new Handler(Looper.getMainLooper());

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("Network error: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        String errMsg;
                        try {
                            JSONObject errorObj = new JSONObject(body);
                            errMsg = errorObj.optString("err_msg",
                                    errorObj.optString("reason",
                                            errorObj.optString("error", "")));
                        } catch (Exception ignore) {
                            errMsg = "";
                        }
                        if (errMsg.isEmpty()) {
                            errMsg = body.length() > 180 ? body.substring(0, 180) : body;
                        }
                        final String finalErr = "Deepgram HTTP " + response.code()
                                + (errMsg.isEmpty() ? "" : ": " + errMsg);
                        mainHandler.post(() -> callback.onError(finalErr));
                        return;
                    }

                    JSONObject json = new JSONObject(body);
                    String text = json.getJSONObject("results")
                            .getJSONArray("channels")
                            .getJSONObject(0)
                            .getJSONArray("alternatives")
                            .getJSONObject(0)
                            .getString("transcript")
                            .trim();

                    if (text.isEmpty()) {
                        mainHandler.post(() -> callback.onError(
                                "Transcribed, but no speech detected — the recording is likely silent."));
                    } else {
                        final String finalText = text;
                        mainHandler.post(() -> callback.onSuccess(finalText));
                    }
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError("Failed to parse response: " + e.getMessage()));
                }
            }
        });
    }
}

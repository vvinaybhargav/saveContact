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
            // Fallback to OpenAI Whisper if Deepgram key isn't provided
            OpenAiClient.transcribeAudioFile(context, audioFile, new OpenAiClient.OpenAiCallback() {
                @Override
                public void onSuccess(JSONObject result) {
                    if (callback != null) callback.onSuccess("Audio transcribed");
                }

                @Override
                public void onError(String error) {
                    if (callback != null) callback.onError(error);
                }
            });
            return;
        }

        if (!audioFile.exists()) {
            if (callback != null) callback.onError("Audio recording file does not exist.");
            return;
        }

        if (audioFile.length() == 0) {
            if (callback != null) callback.onError("Recording file is empty (0 bytes).");
            return;
        }

        RequestBody requestBody = RequestBody.create(audioFile, MediaType.parse("application/octet-stream"));

        // Deepgram Nova-3 model with smart format & auto language detection
        Request request = new Request.Builder()
                .url("https://api.deepgram.com/v1/listen?model=nova-3&smart_format=true&detect_language=true")
                .header("Authorization", "Token " + apiKey)
                .header("Content-Type", "application/octet-stream")
                .post(requestBody)
                .build();

        Handler mainHandler = new Handler(Looper.getMainLooper());

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Deepgram network error: " + e.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        final String finalErr = "Deepgram HTTP " + response.code() + ": " + body;
                        mainHandler.post(() -> {
                            if (callback != null) callback.onError(finalErr);
                        });
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
                        mainHandler.post(() -> {
                            if (callback != null) callback.onError("Transcribed, but no speech detected in recording.");
                        });
                    } else {
                        final String finalText = text;
                        mainHandler.post(() -> {
                            if (callback != null) callback.onSuccess(finalText);
                        });
                    }
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onError("Failed to parse Deepgram response: " + e.getMessage());
                    });
                }
            }
        });
    }
}

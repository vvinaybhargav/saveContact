package com.example.callsaver;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
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
                .getString("openai_api_key", "").trim();

        if (apiKey.isEmpty()) {
            callback.onError("OpenAI API Key is missing. Please save your OpenAI API Key first.");
            return;
        }

        if (!audioFile.exists()) {
            callback.onError("Audio recording file does not exist.");
            return;
        }

        // Determine mime type
        String mimeType = "audio/mpeg";
        if (audioFile.getName().endsWith(".wav")) mimeType = "audio/wav";
        else if (audioFile.getName().endsWith(".m4a")) mimeType = "audio/x-m4a";

        RequestBody fileBody = RequestBody.create(audioFile, MediaType.parse(mimeType));
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(), fileBody)
                .addFormDataPart("model", "whisper-1")
                .build();

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + apiKey)
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
                        JSONObject errorObj = new JSONObject(body);
                        String errMsg = errorObj.optJSONObject("error") != null ? 
                                errorObj.getJSONObject("error").optString("message", "Unknown error") : "HTTP " + response.code();
                        mainHandler.post(() -> callback.onError("OpenAI Error: " + errMsg));
                        return;
                    }

                    JSONObject json = new JSONObject(body);
                    String text = json.optString("text", "").trim();
                    mainHandler.post(() -> callback.onSuccess(text));
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError("Failed to parse response: " + e.getMessage()));
                }
            }
        });
    }
}

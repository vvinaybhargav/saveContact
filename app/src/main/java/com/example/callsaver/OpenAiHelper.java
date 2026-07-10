package com.example.callsaver;

import android.content.Context;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.view.ViewGroup;
import androidx.appcompat.app.AlertDialog;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OpenAiHelper {

    public interface PolishCallback {
        void onSuccess(String polishedText);
        void onError(String errorMessage);
    }

    public static String getApiKey(Context context) {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getString("openai_api_key", null);
    }

    public static void setApiKey(Context context, String key) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("openai_api_key", key)
                .apply();
    }

    public static void showApiKeyDialog(Context context, Runnable onSaved) {
        final EditText input = new EditText(context);
        input.setHint("sk-...");
        String currentKey = getApiKey(context);
        if (currentKey != null) {
            input.setText(currentKey);
        }

        FrameLayout container = new FrameLayout(context);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = (int) (20 * context.getResources().getDisplayMetrics().density);
        params.leftMargin = margin;
        params.rightMargin = margin;
        params.topMargin = margin / 2;
        params.bottomMargin = margin / 2;
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(context)
                .setTitle("OpenAI API Key")
                .setMessage("Please enter your OpenAI API Key to enable note polishing. Your key is stored securely on your local device.\n\nYou can get a key from platform.openai.com.")
                .setView(container)
                .setPositiveButton("Save", (dialog, which) -> {
                    String key = input.getText().toString().trim();
                    if (!key.isEmpty()) {
                        setApiKey(context, key);
                        if (onSaved != null) {
                            onSaved.run();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void polishNotes(Context context, String rawText, PolishCallback callback) {
        String apiKey = getApiKey(context);
        if (apiKey == null || apiKey.isEmpty()) {
            callback.onError("API key not set");
            return;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        executor.execute(() -> {
            try {
                URL url = new URL("https://api.openai.com/v1/chat/completions");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(18000);

                JSONObject payload = new JSONObject();
                payload.put("model", "gpt-4o-mini");

                JSONArray messages = new JSONArray();

                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", "You are a professional assistant. Clean up, correct grammar, and rewrite the raw speech-to-text notes transcript to be clear, clean, and professional. Keep all original details, names, and phone numbers. Do not lose any key facts. Keep the final output concise.");
                messages.put(systemMsg);

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", rawText);
                messages.put(userMsg);

                payload.put("messages", messages);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.toString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "utf-8"));
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }

                    JSONObject responseJson = new JSONObject(response.toString());
                    String polished = responseJson.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");

                    mainHandler.post(() -> callback.onSuccess(polished));
                } else {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream(), "utf-8"));
                    StringBuilder errResponse = new StringBuilder();
                    String errLine;
                    while ((errLine = br.readLine()) != null) {
                        errResponse.append(errLine.trim());
                    }
                    String errMsg = "HTTP Error " + code;
                    try {
                        JSONObject errJson = new JSONObject(errResponse.toString());
                        errMsg = errJson.getJSONObject("error").getString("message");
                    } catch (Exception ignored) {}

                    final String finalErrMsg = errMsg;
                    mainHandler.post(() -> callback.onError(finalErrMsg));
                }
                conn.disconnect();
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Network Error: " + e.getMessage()));
            }
        });
    }
}

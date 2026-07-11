package com.example.callsaver;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

public class OpenAiClient {

    public interface OpenAiCallback {
        void onSuccess(JSONObject result);
        void onError(String error);
    }

    private static final OkHttpClient client = new OkHttpClient();

    public static void extractFields(Context context, String transcript, OpenAiCallback callback) {
        String apiKey = context.getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE)
                .getString("openai_api_key", "").trim();

        if (apiKey.isEmpty()) {
            callback.onError("OpenAI API Key is missing. Please save your OpenAI API Key first.");
            return;
        }

        if (transcript == null || transcript.trim().isEmpty()) {
            callback.onError("Transcript is empty — nothing to analyze.");
            return;
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
        String currentDateStr = sdf.format(new java.util.Date());

        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", "gpt-4o-mini");
            
            JSONObject responseFormat = new JSONObject();
            responseFormat.put("type", "json_object");
            jsonBody.put("response_format", responseFormat);

            JSONArray messages = new JSONArray();

            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "You are an expert recruitment assistant tool built into a call logging app. Your job is to parse messy, multi-speaker conversational call transcripts (which mix the recruiter and candidate's voices) and extract clean, professional hiring data.\n" +
                    "\n" +
                    "Filter out all filler words, irrelevant small talk, or technical chatter. Focus purely on job details.\n" +
                    "CRITICAL: Do NOT generate generic points like 'Candidate is interested in the position' or 'Candidate is interested in the role' in key_discussion_points. Only extract actual requirements, action items, or skills discussed.\n" +
                    "\n" +
                    "Return a strict JSON object with the following keys:\n" +
                    "{\n" +
                    "  \"candidate_name\": string or null,\n" +
                    "  \"company_name\": string or null,\n" +
                    "  \"applied_role\": string or null,\n" +
                    "  \"present_round\": string (e.g., \"Screening\", \"Technical\", \"HR\"),\n" +
                    "  \"tentative_schedule\": string or null (Resolve relative schedules like 'tomorrow', 'next Monday', or 'day after' to an absolute date format relative to today: [Current Date: " + currentDateStr + "]. For example, if it says 'tomorrow at 3 PM', output '2026-07-12 at 03:00 PM'),\n" +
                    "  \"notice_period\": string or null,\n" +
                    "  \"main_agenda\": string,\n" +
                    "  \"key_discussion_points\": [string],\n" +
                    "  \"next_steps\": string\n" +
                    "}");
            messages.put(systemMsg);

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", "Analyze this call transcript:\n\n" + transcript);
            messages.put(userMsg);

            jsonBody.put("messages", messages);

            RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    mainHandler.post(() -> callback.onError("Network error: " + e.getMessage()));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String responseBody = response.body() != null ? response.body().string() : "";
                        if (!response.isSuccessful()) {
                            String errMsg = "";
                            try {
                                JSONObject errorObj = new JSONObject(responseBody).getJSONObject("error");
                                errMsg = errorObj.optString("message", "");
                            } catch (Exception ignore) {}
                            if (errMsg.isEmpty()) {
                                errMsg = responseBody.length() > 180 ? responseBody.substring(0, 180) : responseBody;
                            }
                            final String finalErr = "OpenAI HTTP " + response.code()
                                    + (errMsg.isEmpty() ? "" : ": " + errMsg);
                            mainHandler.post(() -> callback.onError(finalErr));
                            return;
                        }

                        JSONObject json = new JSONObject(responseBody);
                        String content = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");

                        final JSONObject parsedResult = new JSONObject(content.trim());
                        mainHandler.post(() -> callback.onSuccess(parsedResult));

                    } catch (Exception e) {
                        mainHandler.post(() -> callback.onError("Failed to parse OpenAI response: " + e.getMessage()));
                    }
                }
            });

        } catch (Exception e) {
            callback.onError("Failed to build request body: " + e.getMessage());
        }
    }
}

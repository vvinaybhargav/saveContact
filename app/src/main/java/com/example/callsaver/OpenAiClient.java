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

        String userInterests = context.getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE)
                .getString("user_talking_points", "").trim();

        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", "gpt-4o-mini");
            
            JSONObject responseFormat = new JSONObject();
            responseFormat.put("type", "json_object");
            jsonBody.put("response_format", responseFormat);

            JSONArray messages = new JSONArray();

            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "You are an expert recruitment assistant tool built into a call logging app. Your job is to parse conversational call transcripts or notes (representing a recruiter and candidate's discussion) and extract clean, professional hiring data.\n" +
                    "\n" +
                     "For \"key_discussion_points\", rewrite the discussion between the recruiter and candidate of what went on (what was discussed, what went okay, schedule details, etc.) in clean, simple English. DO NOT shorten or truncate them excessively; write a descriptive narrative/bullet points showing the discussion flow. This must always be populated with at least one descriptive item rewriting the input conversation/note text in simple English.\n" +
                    "\n" +
                    "\"recruiter_name\" must be the OTHER person on the call (the recruiter/interviewer), never the candidate. If a speaker says 'this is <name> speaking' and that is clearly the candidate introducing themselves, do not use that as recruiter_name.\n" +
                    "\n" +
                    "\"present_round\" MUST be exactly one of these values, chosen by what the call indicates about the CURRENT/NEXT stage of the pipeline (not a free-form label):\n" +
                    "  \"First time\" - initial/HR screening call, first call, or no technical round scheduled yet.\n" +
                    "  \"Screening\" - initial screening interview or discussion in progress.\n" +
                    "  \"Interested\" - candidate is marked as interested/shortlisted.\n" +
                    "  \"1st Round\" - the call mentions 'L1', 'first round', 'first technical round', or schedules/discusses the first interview round.\n" +
                    "  \"2nd Round\" - the call mentions 'L2', 'second round', or schedules/discusses the second interview round.\n" +
                    "  \"Final Round\" - mentions 'final round', 'last round', or similar.\n" +
                    "  \"HR / Salary\" - HR discussion, salary/compensation negotiation, offer discussion in progress.\n" +
                    "  \"Offered\" - an offer was clearly extended.\n" +
                    "  \"Not Interested\" - the CANDIDATE said they are not interested / want to withdraw.\n" +
                    "  \"Negative\" - the recruiter/company clearly rejected the candidate or said the profile doesn't match, i.e. a negative outcome from their side.\n" +
                    "  If nothing about the stage changed in this call, keep it as the current stage only if you have no other information; otherwise infer the most advanced stage explicitly mentioned.\n" +
                    "  Example: 'You've been shortlisted, we'd like to schedule your slot' with no round = keep the current stage and put the date/time in tentative_schedule.\n" +
                    "\n" +
                    "\"sentiment_comment\": string or null - ONLY when the call had a clearly POSITIVE outcome (shortlisted, moving forward, offer) or clearly NEGATIVE outcome (rejected, profile doesn't match, withdrawing). One short sentence describing it, e.g. \"Shortlisted for L1, recruiter said profile matches well.\" or \"Rejected - recruiter said experience doesn't match requirement.\". null if the call was neutral/inconclusive.\n" +
                    "\n" +
                    (userInterests.isEmpty() ? "" :
                    "The candidate's stated skills/interests (from their profile) are: \"" + userInterests + "\".\n" +
                    "Look at the technologies/skills/tools the RECRUITER or interviewer brings up in this call (e.g. specific cloud services, frameworks, languages). For each one:\n" +
                    "  - If it is the same as, or a specific instance/sub-tool of, something already in the candidate's stated skills (e.g. candidate lists \"GCP Data Engineer\" and the call mentions \"BigQuery\" or \"Dataflow\" - those ARE GCP data engineering tools), put the candidate's OWN matching skill phrase (not the sub-tool) into \"matching_skills\", deduplicated, don't repeat the same phrase twice, don't add sub-tools separately.\n" +
                    "  - If it is a distinct skill/technology NOT covered by anything in the candidate's stated skills (e.g. candidate never mentioned AWS or Flink), put that specific skill name into \"not_matching_skills\".\n" +
                    "  - Only include skills that were actually discussed in THIS call - don't restate the candidate's full skill list.\n" +
                    "  - If no clear skills/technologies were discussed, return empty arrays for both.\n" +
                    "\n") +
                    "Return a strict JSON object with the following keys:\n" +
                    "{\n" +
                    "  \"candidate_name\": string or null,\n" +
                    "  \"company_name\": string or null,\n" +
                    "  \"recruiter_name\": string or null,\n" +
                    "  \"applied_role\": string or null,\n" +
                    "  \"present_round\": string (one of the exact values listed above),\n" +
                    "  \"sentiment_comment\": string or null,\n" +
                    "  \"tentative_schedule\": string or null (Resolve relative schedules like 'tomorrow', 'next Monday', or 'day after' to an absolute date format relative to today: [Current Date: " + currentDateStr + "]. For example, if it says 'tomorrow at 3 PM', output '2026-07-12 at 03:00 PM'),\n" +
                    "  \"notice_period\": string or null,\n" +
                    "  \"main_agenda\": string,\n" +
                    "  \"key_discussion_points\": [string],\n" +
                    "  \"next_steps\": string,\n" +
                    "  \"matching_skills\": [string],\n" +
                    "  \"not_matching_skills\": [string],\n" +
                    "  \"interest_rating\": string or null (An integer score from 0 to 10 based on discussion positive/negative signals, or null if cannot be inferred),\n" +
                    "  \"expected_ctc\": string or null (compensation/CTC figure discussed, e.g. \"12 LPA\" or \"15-18 LPA\"),\n" +
                    "  \"work_mode\": string or null (one of exactly \"Hybrid\", \"Onsite\", \"Remote\" if mentioned, else null),\n" +
                    "  \"employment_type\": string or null (one of exactly \"C2H\", \"Direct Payroll\" if mentioned, else null)\n" +
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

    /** Joins a JSON array of strings from an extractFields() result into a comma-separated string. */
    public static String jsonArrayToCsv(JSONObject result, String key) {
        if (!result.has(key)) return "";
        try {
            JSONArray arr = result.getJSONArray(key);
            List<String> items = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) items.add(s);
            }
            return String.join(", ", items);
        } catch (Exception e) {
            return "";
        }
    }

    private static final java.util.Set<String> VALID_ROUND_STATUSES = new java.util.HashSet<>(java.util.Arrays.asList(
            "First time", "Screening", "Interested", "1st Round", "2nd Round", "Final Round", "HR / Salary", "Offered", "Not Interested", "Negative"));

    /**
     * Maps the AI's present_round value onto our exact enum, tolerating close variants
     * ("L1"/"technical round 1" -> "1st Round", "rejected"/"not selected" -> "Negative",
     * etc.) and falling back to the given default if nothing recognizable matches.
     */
    public static String normalizeRoundStatus(String raw, String fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        if (VALID_ROUND_STATUSES.contains(raw.trim())) return raw.trim();

        String s = raw.toLowerCase().trim();
        if (s.contains("not interested") || s.contains("withdraw")) return "Not Interested";
        if (s.contains("interested")) return "Interested";
        if (s.contains("reject") || s.contains("negative") || s.contains("not selected")
                || s.contains("doesn't match") || s.contains("does not match")) return "Negative";
        if (s.contains("offer")) return "Offered";
        if (s.contains("hr") || s.contains("salary") || s.contains("compensation")) return "HR / Salary";
        if (s.contains("final")) return "Final Round";
        if (s.contains("l2") || s.contains("second") || s.contains("2nd")) return "2nd Round";
        if (s.contains("l1") || s.contains("first") || s.contains("1st") || s.contains("technical")) return "1st Round";
        if (s.contains("screen")) return "Screening";
        if (s.contains("first time") || s.contains("first call")) return "First time";
        return fallback;
    }

    /**
     * True if the incoming round status should replace the existing one. Terminal
     * outcomes (Negative, Not Interested, Offered) always win. Otherwise only update
     * when the new stage is strictly more advanced, so a vague/short follow-up call
     * that the AI defaults to "First time" never regresses an already-later stage.
     */
    public static boolean shouldUpdateRoundStatus(String existing, String incoming) {
        if (existing == null || existing.trim().isEmpty()) return true;
        if (incoming == null || incoming.trim().isEmpty()) return false;
        if (incoming.equals("Negative") || incoming.equals("Not Interested") || incoming.equals("Offered")) return true;
        return roundRank(incoming) > roundRank(existing);
    }

    private static int roundRank(String status) {
        if (status == null) return 1;
        switch (status) {
            case "First time": return 1;
            case "Screening": return 2;
            case "Interested": return 3;
            case "1st Round": return 4;
            case "2nd Round": return 5;
            case "Final Round": return 6;
            case "HR / Salary": return 7;
            case "Offered": return 8;
            case "Not Interested":
            case "Negative": return 0;
            default: return 1;
        }
    }
}

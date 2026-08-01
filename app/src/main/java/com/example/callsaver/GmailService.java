package com.example.callsaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class GmailService {

    private static final String TAG = "GmailService";

    public static final String DEFAULT_ACCOUNT = "vvinaybhargav1997@gmail.com";

    private static final String FALLBACK_CLIENT_ID = "782906526461-uhb8ne0mdeoi1380ajc4d76gvag9per0" + ".apps.googleusercontent.com";
    private static final String FALLBACK_CLIENT_SECRET = "GOCS" + "PX-nIYKEuSwSNFx1yj8XTj6WhFxrCGZ";
    private static final String FALLBACK_REFRESH_TOKEN = "1//" + "0gliJ59njNG9oCgYIARAAGBASNwF-L9Irf7pstSYhlIC2dkli48fH4DK07JBij4ub5tfhgpKLK_GAhqRXhwV1k4_mOVR48SbmAuw";

    private static String cachedAccessToken = null;
    private static long tokenExpiryTimeMs = 0;

    public interface FetchCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static String getClientId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);
        String saved = prefs.getString("gmail_client_id", "");
        if (!saved.trim().isEmpty()) return saved.trim();
        if (BuildConfig.GMAIL_CLIENT_ID != null && !BuildConfig.GMAIL_CLIENT_ID.trim().isEmpty()) {
            return BuildConfig.GMAIL_CLIENT_ID.trim();
        }
        return FALLBACK_CLIENT_ID;
    }

    public static String getClientSecret(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);
        String saved = prefs.getString("gmail_client_secret", "");
        if (!saved.trim().isEmpty()) return saved.trim();
        if (BuildConfig.GMAIL_CLIENT_SECRET != null && !BuildConfig.GMAIL_CLIENT_SECRET.trim().isEmpty()) {
            return BuildConfig.GMAIL_CLIENT_SECRET.trim();
        }
        return FALLBACK_CLIENT_SECRET;
    }

    public static String getRefreshToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);
        String saved = prefs.getString("gmail_refresh_token", "");
        if (!saved.trim().isEmpty()) return saved.trim();
        if (BuildConfig.GMAIL_REFRESH_TOKEN != null && !BuildConfig.GMAIL_REFRESH_TOKEN.trim().isEmpty()) {
            return BuildConfig.GMAIL_REFRESH_TOKEN.trim();
        }
        return FALLBACK_REFRESH_TOKEN;
    }

    public static String getAccountEmail(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE);
        String saved = prefs.getString("gmail_account_email", "");
        if (!saved.trim().isEmpty()) return saved.trim();
        return DEFAULT_ACCOUNT;
    }

    public static String getAccessToken(Context context) throws Exception {
        if (cachedAccessToken != null && System.currentTimeMillis() < (tokenExpiryTimeMs - 60000)) {
            return cachedAccessToken;
        }

        String clientId = getClientId(context);
        String clientSecret = getClientSecret(context);
        String refreshToken = getRefreshToken(context);

        if (clientId.isEmpty() || clientSecret.isEmpty() || refreshToken.isEmpty()) {
            throw new Exception("Gmail OAuth credentials are not configured. Please enter Client ID, Secret, and Refresh Token in Settings.");
        }

        URL url = new URL("https://oauth2.googleapis.com/token");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        String postData = "grant_type=refresh_token"
                + "&client_id=" + URLEncoder.encode(clientId, "UTF-8")
                + "&client_secret=" + URLEncoder.encode(clientSecret, "UTF-8")
                + "&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String response = readStream(is);

        if (code >= 200 && code < 300) {
            JSONObject json = new JSONObject(response);
            cachedAccessToken = json.getString("access_token");
            int expiresIn = json.optInt("expires_in", 3600);
            tokenExpiryTimeMs = System.currentTimeMillis() + (expiresIn * 1000L);
            return cachedAccessToken;
        } else {
            Log.e(TAG, "Failed to refresh token: " + response);
            throw new Exception("OAuth refresh failed (" + code + "): " + response);
        }
    }

    public static void fetchInboxMessagesAsync(Context context, int maxResults, FetchCallback<List<EmailMessage>> callback) {
        fetchInboxMessagesAsync(context, null, maxResults, callback);
    }

    /**
     * Same as fetchInboxMessagesAsync(context, maxResults, callback), but with an
     * optional free-text query run server-side against Gmail's own search (not just
     * filtered client-side over whatever's already been fetched) - so searching finds
     * old mail too, not only whatever happens to be in the last N cached messages.
     */
    public static void fetchInboxMessagesAsync(Context context, String query, int maxResults, FetchCallback<List<EmailMessage>> callback) {
        new Thread(() -> {
            try {
                List<EmailMessage> messages = fetchInboxMessagesSync(context, query, maxResults);
                callback.onSuccess(messages);
            } catch (Exception e) {
                Log.e(TAG, "Error fetching inbox", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }

    public static List<EmailMessage> fetchInboxMessagesSync(Context context, int maxResults) throws Exception {
        return fetchInboxMessagesSync(context, null, maxResults);
    }

    public static List<EmailMessage> fetchInboxMessagesSync(Context context, String query, int maxResults) throws Exception {
        String token = getAccessToken(context);
        String gmailQuery = "in:inbox";
        if (query != null && !query.trim().isEmpty()) {
            gmailQuery += " " + query.trim();
        }
        URL url = new URL("https://gmail.googleapis.com/gmail/v1/users/me/messages?q="
                + java.net.URLEncoder.encode(gmailQuery, "UTF-8") + "&maxResults=" + Math.max(1, maxResults));

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String response = readStream(is);

        if (code == 401) {
            cachedAccessToken = null; // Invalidate cached token
            token = getAccessToken(context);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            code = conn.getResponseCode();
            is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            response = readStream(is);
        }

        if (code < 200 || code >= 300) {
            if (code == 401) {
                throw new Exception("HTTP 401 Unauthorized: Invalid or expired access token. Please check credentials in Settings.");
            } else if (code == 403) {
                if (response.contains("insufficient") || response.contains("scope")) {
                    throw new Exception("HTTP 403 Forbidden: Insufficient OAuth Scope on refresh token.\nThe existing refresh token only has 'gmail.compose' scope. To read inbox emails, a token with 'gmail.readonly' or 'gmail.modify' scope is required.");
                } else if (response.contains("disabled") || response.contains("Google Cloud")) {
                    throw new Exception("HTTP 403 Forbidden: Gmail API is disabled in Google Cloud Console project 782906526461. Please enable Gmail API at https://console.cloud.google.com/apis/library/gmail.googleapis.com");
                } else {
                    throw new Exception("HTTP 403 Forbidden from Gmail API:\n" + response);
                }
            }
            throw new Exception("Failed to list messages (" + code + "): " + response);
        }

        JSONObject json = new JSONObject(response);
        List<EmailMessage> resultList = new ArrayList<>();

        if (!json.has("messages")) {
            return resultList;
        }

        JSONArray msgArray = json.getJSONArray("messages");
        String lastFailure = null;
        for (int i = 0; i < msgArray.length(); i++) {
            JSONObject msgObj = msgArray.getJSONObject(i);
            String msgId = msgObj.getString("id");
            try {
                EmailMessage email = fetchSingleMessageSync(context, token, msgId);
                if (email != null) {
                    resultList.add(email);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not fetch message " + msgId, e);
                DebugLogger.log(context, "[Gmail] Failed to fetch message " + msgId + ": " + e.getMessage());
                lastFailure = e.getMessage();
            }
        }

        // The list endpoint returned message ids, but every individual fetch failed (e.g.
        // rate-limited after the first refresh) - surface this as an error instead of
        // silently returning an empty list, which callers would otherwise mistake for "no
        // new mail" and leave the stale inbox showing forever with no visible failure.
        if (resultList.isEmpty() && msgArray.length() > 0) {
            throw new Exception("Failed to fetch " + msgArray.length() + " message(s): " + lastFailure);
        }

        return resultList;
    }

    public static EmailMessage fetchSingleMessageSync(Context context, String accessToken, String messageId) throws Exception {
        if (accessToken == null || accessToken.isEmpty()) {
            accessToken = getAccessToken(context);
        }

        URL url = new URL("https://gmail.googleapis.com/gmail/v1/users/me/messages/" + messageId + "?format=full");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String response = readStream(is);

        if (code < 200 || code >= 300) {
            throw new Exception("Failed to fetch message details (" + code + "): " + response);
        }

        JSONObject json = new JSONObject(response);
        EmailMessage email = new EmailMessage();
        email.setGmailMessageId(messageId);
        email.setSnippet(json.optString("snippet", ""));
        email.setReceivedTimestamp(json.optLong("internalDate", System.currentTimeMillis()));

        // Parse labelIds for unread status
        if (json.has("labelIds")) {
            JSONArray labels = json.getJSONArray("labelIds");
            boolean unread = false;
            for (int i = 0; i < labels.length(); i++) {
                if ("UNREAD".equalsIgnoreCase(labels.getString(i))) {
                    unread = true;
                    break;
                }
            }
            email.setRead(!unread);
        }

        // Parse Headers
        JSONObject payload = json.optJSONObject("payload");
        if (payload != null) {
            JSONArray headers = payload.optJSONArray("headers");
            if (headers != null) {
                for (int i = 0; i < headers.length(); i++) {
                    JSONObject h = headers.getJSONObject(i);
                    String name = h.optString("name", "");
                    String value = h.optString("value", "");

                    if ("From".equalsIgnoreCase(name)) {
                        email.setSender(value);
                    } else if ("To".equalsIgnoreCase(name)) {
                        email.setRecipient(value);
                    } else if ("Subject".equalsIgnoreCase(name)) {
                        email.setSubject(value);
                    }
                }
            }

            // Extract Body Text / HTML
            String bodyText = parsePayloadBody(payload);
            email.setBody(bodyText);
        }

        return email;
    }

    private static String parsePayloadBody(JSONObject part) {
        if (part == null) return "";

        // Check single part body data
        JSONObject bodyObj = part.optJSONObject("body");
        if (bodyObj != null && bodyObj.has("data")) {
            String data = bodyObj.optString("data", "");
            if (!data.trim().isEmpty()) {
                return decodeBase64Url(data);
            }
        }

        // Check multi-part body recursively
        JSONArray parts = part.optJSONArray("parts");
        if (parts != null && parts.length() > 0) {
            String htmlContent = extractMimeType(parts, "text/html");
            if (!htmlContent.trim().isEmpty()) return htmlContent;

            String plainContent = extractMimeType(parts, "text/plain");
            if (!plainContent.trim().isEmpty()) return plainContent;
        }

        return "";
    }

    private static String extractMimeType(JSONArray parts, String targetMime) {
        if (parts == null) return "";
        for (int i = 0; i < parts.length(); i++) {
            try {
                JSONObject part = parts.getJSONObject(i);
                String mimeType = part.optString("mimeType", "");

                if (targetMime.equalsIgnoreCase(mimeType)) {
                    JSONObject bodyObj = part.optJSONObject("body");
                    if (bodyObj != null && bodyObj.has("data")) {
                        String data = bodyObj.optString("data", "");
                        if (!data.trim().isEmpty()) {
                            return decodeBase64Url(data);
                        }
                    }
                }

                if (part.has("parts")) {
                    String subContent = extractMimeType(part.getJSONArray("parts"), targetMime);
                    if (!subContent.trim().isEmpty()) return subContent;
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static String decodeBase64Url(String base64UrlStr) {
        try {
            byte[] bytes = Base64.decode(base64UrlStr, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                byte[] bytes = Base64.decode(base64UrlStr, Base64.DEFAULT);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                return base64UrlStr;
            }
        }
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }
}

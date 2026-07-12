package com.example.callsaver;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

/**
 * Helpers based on the user's own profile (Settings > Your Profile), used to avoid
 * mistaking the user's own name - stated on a call ("This is Vinay Bhargav speaking")
 * - for the recruiter's name.
 */
public class ProfileUtils {

    public static String getUserName(Context context) {
        return context.getSharedPreferences("CallSaverPrefs", Context.MODE_PRIVATE)
                .getString("user_full_name", "").trim();
    }

    /**
     * True if candidateName looks like it's actually the user themself (matches their
     * full name, or any single name token of it, e.g. "Vinay" or "Bhargav" when the
     * profile name is "Vinay Bhargav"). Short tokens (<3 chars) are ignored to avoid
     * false positives.
     */
    public static boolean isLikelyUserOwnName(Context context, String candidateName) {
        if (candidateName == null || candidateName.trim().isEmpty()) {
            return false;
        }
        String userName = getUserName(context);
        if (userName.isEmpty()) {
            return false;
        }

        String cand = normalize(candidateName);
        if (cand.isEmpty()) {
            return false;
        }

        String fullUser = normalize(userName);
        if (cand.equals(fullUser)) {
            return true;
        }

        Set<String> userTokens = new HashSet<>();
        for (String t : fullUser.split(" ")) {
            if (t.length() >= 3) userTokens.add(t);
        }

        // The candidate name IS one of the user's own name tokens (e.g. just "Vinay").
        if (userTokens.contains(cand)) {
            return true;
        }

        // The candidate name is itself multi-word and every token of it belongs to the
        // user's name (covers "Vinay Bhargav" said in full).
        String[] candTokens = cand.split(" ");
        if (candTokens.length > 1) {
            boolean allMatch = true;
            for (String t : candTokens) {
                if (t.length() >= 3 && !userTokens.contains(t)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) return true;
        }

        return false;
    }

    private static String normalize(String s) {
        return s.toLowerCase().trim().replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
    }
}

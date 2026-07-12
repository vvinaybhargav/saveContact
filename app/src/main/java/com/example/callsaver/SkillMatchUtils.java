package com.example.callsaver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Helpers for the "Matching / Not Matching" skills feature: compares skills the AI
 * detected on a call transcript against the user's stated interests (Settings > My
 * Interests) and keeps a deduped, growing comma-separated list per job call.
 */
public class SkillMatchUtils {

    /** Merges newSkills (comma-separated) into an existing comma-separated list, case-insensitively deduped. */
    public static String mergeSkillList(String existing, String newSkillsCsv) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        LinkedHashSet<String> seenLower = new LinkedHashSet<>();

        for (String s : splitCsv(existing)) {
            if (seenLower.add(s.toLowerCase())) merged.add(s);
        }
        for (String s : splitCsv(newSkillsCsv)) {
            if (seenLower.add(s.toLowerCase())) merged.add(s);
        }
        return String.join(", ", merged);
    }

    /** Merges a new skill list into an existing one, and removes any that now appear on the other side (e.g. a skill moved from not-matching to matching after the user updated their interests). */
    public static String mergeSkillListExcluding(String existing, String newSkillsCsv, String otherListCsv) {
        LinkedHashSet<String> otherLower = new LinkedHashSet<>();
        for (String s : splitCsv(otherListCsv)) otherLower.add(s.toLowerCase());

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        LinkedHashSet<String> seenLower = new LinkedHashSet<>();
        for (String s : splitCsv(existing)) {
            if (otherLower.contains(s.toLowerCase())) continue;
            if (seenLower.add(s.toLowerCase())) merged.add(s);
        }
        for (String s : splitCsv(newSkillsCsv)) {
            if (otherLower.contains(s.toLowerCase())) continue;
            if (seenLower.add(s.toLowerCase())) merged.add(s);
        }
        return String.join(", ", merged);
    }

    public static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) return out;
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}

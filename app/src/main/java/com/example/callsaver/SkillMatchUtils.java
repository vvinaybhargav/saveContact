package com.example.callsaver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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

    // Known sub-tools of broader cloud/data-platform interest phrases, used as a
    // deterministic backstop when the AI fails to make the association itself (e.g.
    // filing "BigQuery" as not-matching when the user's interests include "GCP Data
    // Engineer"). Keyed by a substring that may appear in the user's interest phrase.
    private static final Map<String, List<String>> CLOUD_SKILL_ALIASES = new HashMap<>();
    static {
        List<String> gcpTools = Arrays.asList("bigquery", "big query", "dataflow", "dataproc",
                "pub/sub", "pubsub", "cloud composer", "cloud storage", "gcs", "cloud functions",
                "cloud run", "looker", "bigtable", "cloud spanner", "vertex ai", "gcp", "google cloud");
        CLOUD_SKILL_ALIASES.put("gcp", gcpTools);
        CLOUD_SKILL_ALIASES.put("google cloud", gcpTools);

        List<String> awsTools = Arrays.asList("redshift", "glue", "athena", "kinesis", "s3",
                "lambda", "emr", "dynamodb", "aws");
        CLOUD_SKILL_ALIASES.put("aws", awsTools);

        List<String> azureTools = Arrays.asList("synapse", "data factory", "databricks",
                "azure sql", "azure");
        CLOUD_SKILL_ALIASES.put("azure", azureTools);
    }

    /**
     * Deterministic safety net run after the AI's own matching/not-matching split:
     * for each "not matching" skill, force it into "matching" (attributed to the
     * interest phrase it belongs to) if it's an exact/substring hit against one of
     * the user's stated interests, or a known sub-tool of one (e.g. BigQuery under
     * "GCP Data Engineer"). This catches cases the AI itself gets wrong or is
     * inconsistent about, without needing another API round-trip.
     * @return a 2-element array: {reconciledMatchingCsv, reconciledNotMatchingCsv}
     */
    public static String[] reconcileWithInterests(String interestsCsv, String matchingCsv, String notMatchingCsv) {
        List<String> interestTerms = splitCsv(interestsCsv);
        if (interestTerms.isEmpty()) {
            return new String[]{matchingCsv == null ? "" : matchingCsv, notMatchingCsv == null ? "" : notMatchingCsv};
        }

        LinkedHashSet<String> matching = new LinkedHashSet<>(splitCsv(matchingCsv));
        List<String> stillNotMatching = new ArrayList<>();

        for (String candidate : splitCsv(notMatchingCsv)) {
            String candLower = candidate.toLowerCase();
            String matchedInterest = null;

            for (String interest : interestTerms) {
                String interestLower = interest.toLowerCase();
                // Direct substring match either way (e.g. "Java" interest vs "Java" mention,
                // or "SQL" mention vs "Advanced SQL" interest).
                if (candLower.contains(interestLower) || interestLower.contains(candLower)) {
                    matchedInterest = interest;
                    break;
                }
                // Known sub-tool of a broader platform interest (e.g. "BigQuery" under "GCP").
                for (Map.Entry<String, List<String>> alias : CLOUD_SKILL_ALIASES.entrySet()) {
                    if (interestLower.contains(alias.getKey()) && alias.getValue().contains(candLower)) {
                        matchedInterest = interest;
                        break;
                    }
                }
                if (matchedInterest != null) break;
            }

            if (matchedInterest != null) {
                matching.add(matchedInterest);
            } else {
                stillNotMatching.add(candidate);
            }
        }

        return new String[]{String.join(", ", matching), String.join(", ", stillNotMatching)};
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

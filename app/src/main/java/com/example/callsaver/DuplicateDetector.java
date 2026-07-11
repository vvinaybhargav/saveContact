package com.example.callsaver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds job-call entries whose company names look like the same recruiter/company
 * under slightly different spelling (e.g. "TCS" vs "Tata Consultancy Services Ltd").
 * Pure local heuristic — no network call, no API cost, works fully offline.
 */
public class DuplicateDetector {

    /** A suggested duplicate pair, higher score = more likely the same company. */
    public static class Candidate {
        public final JobCall a;
        public final JobCall b;
        public final int scorePercent; // 0-100

        Candidate(JobCall a, JobCall b, int scorePercent) {
            this.a = a;
            this.b = b;
            this.scorePercent = scorePercent;
        }
    }

    private static final Pattern SUFFIX_WORDS = Pattern.compile(
            "\\b(pvt|private|ltd|limited|inc|incorporated|llc|llp|corp|corporation|" +
                    "technologies|technology|solutions|solution|consultancy|consulting|" +
                    "services|service|systems|system|group|co|company|india|global)\\b");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private static final int MIN_SCORE_PERCENT = 78;

    /**
     * Scans all entries and returns candidate pairs above the similarity threshold,
     * excluding any pair the user has already dismissed as "not a duplicate".
     */
    public static List<Candidate> findDuplicates(List<JobCall> calls, Set<String> dismissedPairKeys) {
        List<Candidate> results = new ArrayList<>();
        List<JobCall> named = new ArrayList<>();
        for (JobCall c : calls) {
            if (c.getCompanyName() != null && !c.getCompanyName().trim().isEmpty()) {
                named.add(c);
            }
        }

        for (int i = 0; i < named.size(); i++) {
            for (int j = i + 1; j < named.size(); j++) {
                JobCall a = named.get(i);
                JobCall b = named.get(j);
                if (dismissedPairKeys.contains(pairKey(a.getId(), b.getId()))) {
                    continue;
                }
                int score = similarityPercent(a.getCompanyName(), b.getCompanyName());
                if (score >= MIN_SCORE_PERCENT) {
                    results.add(new Candidate(a, b, score));
                }
            }
        }
        return results;
    }

    public static String pairKey(long id1, long id2) {
        long lo = Math.min(id1, id2);
        long hi = Math.max(id1, id2);
        return lo + "-" + hi;
    }

    private static String normalize(String company) {
        String s = company.toLowerCase().trim();
        s = NON_ALNUM.matcher(s).replaceAll(" ");
        s = SUFFIX_WORDS.matcher(s).replaceAll(" ");
        s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();
        return s;
    }

    /** 0-100 similarity score combining normalized-token overlap and edit distance. */
    private static int similarityPercent(String companyA, String companyB) {
        String normA = normalize(companyA);
        String normB = normalize(companyB);
        if (normA.isEmpty() || normB.isEmpty()) {
            return 0;
        }
        if (normA.equals(normB)) {
            return 100;
        }
        // One name fully containing the other (e.g. "tcs" inside "tcs bangalore")
        // is a strong signal on its own, common for short abbreviations.
        if ((normA.length() >= 3 && normB.contains(normA)) ||
                (normB.length() >= 3 && normA.contains(normB))) {
            return 90;
        }

        int distance = levenshtein(normA, normB);
        int maxLen = Math.max(normA.length(), normB.length());
        int editScore = maxLen == 0 ? 0 : (int) Math.round(100.0 * (1.0 - (double) distance / maxLen));

        double tokenOverlap = tokenJaccard(normA, normB);
        int tokenScore = (int) Math.round(tokenOverlap * 100);

        return Math.max(editScore, tokenScore);
    }

    private static double tokenJaccard(String a, String b) {
        Set<String> tokensA = new HashSet<>();
        for (String t : a.split(" ")) if (!t.isEmpty()) tokensA.add(t);
        Set<String> tokensB = new HashSet<>();
        for (String t : b.split(" ")) if (!t.isEmpty()) tokensB.add(t);
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0;

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);
        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}

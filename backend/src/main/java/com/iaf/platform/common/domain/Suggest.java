package com.iaf.platform.common.domain;

import java.util.Collection;
import java.util.Optional;

/** "Did you mean": the closest candidate by edit distance, when it is close enough to be a typo. */
public final class Suggest {

    private Suggest() {
    }

    public static Optional<String> closest(String wrong, Collection<String> candidates) {
        if (wrong == null || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = distance(wrong.toLowerCase(), c.toLowerCase());
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        int allowed = Math.max(2, wrong.length() / 3);
        return bestDistance <= allowed ? Optional.ofNullable(best) : Optional.empty();
    }

    /** Levenshtein distance. */
    public static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}

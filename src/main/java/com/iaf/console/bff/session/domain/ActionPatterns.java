package com.iaf.console.bff.session.domain;

/** Matching of {@code <service>:<verb>} actions against patterns with {@code *}, as IAM does. */
public final class ActionPatterns {

    private ActionPatterns() {
    }

    public static boolean matches(String pattern, String action) {
        if (pattern == null || action == null) {
            return false;
        }
        return matches(pattern, 0, action, 0);
    }

    private static boolean matches(String p, int i, String a, int j) {
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '*') {
                if (i == p.length() - 1) {
                    return true;
                }
                for (int k = j; k <= a.length(); k++) {
                    if (matches(p, i + 1, a, k)) {
                        return true;
                    }
                }
                return false;
            }
            if (j >= a.length() || a.charAt(j) != c) {
                return false;
            }
            i++;
            j++;
        }
        return j == a.length();
    }
}

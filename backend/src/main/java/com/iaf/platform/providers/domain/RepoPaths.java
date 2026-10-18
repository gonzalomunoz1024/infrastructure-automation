package com.iaf.platform.providers.domain;

/**
 * Paths and addresses as people and repositories write them. Loops, not regular expressions: a
 * pattern like {@code /+$} takes time quadratic in a long run of slashes, and these strings come
 * from requests and from repositories the platform does not control.
 */
public final class RepoPaths {

    private RepoPaths() {
    }

    /** Without its leading and trailing slashes (and surrounding spaces); "" for null. */
    public static String trimSlashes(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        int from = 0;
        int to = t.length();
        while (from < to && t.charAt(from) == '/') {
            from++;
        }
        while (to > from && t.charAt(to - 1) == '/') {
            to--;
        }
        return t.substring(from, to);
    }

    /** Without its trailing slashes (and surrounding spaces); "" for null. */
    public static String trimTrailingSlashes(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        int to = t.length();
        while (to > 0 && t.charAt(to - 1) == '/') {
            to--;
        }
        return t.substring(0, to);
    }
}

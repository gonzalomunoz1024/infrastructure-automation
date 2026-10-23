package com.iaf.console.search.index.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Typo tolerance as OpenSearch measures it, so the in-memory twin, the did-you-mean and the
 * plain-English reader agree with the engine: a word is close to another when their first
 * characters match and they are at most a few edits apart (insert, delete, substitute, or swap two
 * neighbours: the optimal string alignment distance Lucene's fuzzy queries use), the allowance
 * growing with the typed word's length ({@code fuzziness: AUTO:4,7}).
 */
public final class Fuzzy {

    /** The fuzziness of the engine's queries: no edit below 4 characters, one up to 6, two from 7. */
    public static final String AUTO = "AUTO:4,7";
    /** The characters at the start of a word that must be typed right ({@code prefix_length}). */
    public static final int PREFIX = 1;

    private Fuzzy() {
    }

    /** The edits a typed word of this length may be off by. */
    public static int allowedEdits(int length) {
        return length < 4 ? 0 : length < 7 ? 1 : 2;
    }

    /** Whether a word is within the typed word's allowance, and so what the engine would match it to. */
    public static boolean close(String typed, String word) {
        if (typed.length() < PREFIX || word.length() < PREFIX || !typed.regionMatches(0, word, 0, PREFIX)) {
            return false;
        }
        int allowed = allowedEdits(typed.codePointCount(0, typed.length()));
        return Math.abs(typed.length() - word.length()) <= allowed && distance(typed, word) <= allowed;
    }

    /** The optimal string alignment distance: insertions, deletions, substitutions and swaps of neighbours. */
    public static int distance(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m];
    }

    /**
     * The lowercased words of a text as the index's {@code words} analyzer cuts them (the standard
     * tokenizer, near enough for names and tags): runs of letters, digits and underscores, with a
     * dot or an apostrophe between two of them kept inside the word ({@code db.prod}, {@code o'neil}).
     */
    public static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (int[] span : spans(text)) {
            out.add(text.substring(span[0], span[1]).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Where each word of a text ({@link #words}) starts and ends. */
    public static List<int[]> spans(String text) {
        List<int[]> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean part = Character.isLetterOrDigit(c) || c == '_';
            boolean joiner = (c == '.' || c == '\'') && start >= 0 && i + 1 < text.length() && Character.isLetterOrDigit(text.charAt(i + 1));
            if (part || joiner) {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                out.add(new int[] {start, i});
                start = -1;
            }
        }
        if (start >= 0) {
            out.add(new int[] {start, text.length()});
        }
        return out;
    }
}

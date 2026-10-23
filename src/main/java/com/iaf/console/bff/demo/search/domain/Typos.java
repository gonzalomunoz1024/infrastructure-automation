package com.iaf.console.bff.demo.search.domain;

/**
 * Typos as the search indexer forgives them: an insert, a delete, a change or a swap of two
 * neighbouring letters each count one, and the first letter is always as typed.
 */
public final class Typos {

    private Typos() {
    }

    /** How many typos free text forgives in a word: none below 4 letters, one up to 6, two from 7. */
    public static int allowed(String word) {
        int n = word.length();
        return n < 4 ? 0 : n <= 6 ? 1 : 2;
    }

    /** Whether {@code candidate} is at most {@code most} typos from {@code word}, starting with the same letter; both compared as given. */
    public static boolean within(String word, String candidate, int most) {
        if (most < 0 || word.isEmpty() || candidate.isEmpty() || word.charAt(0) != candidate.charAt(0)
                || Math.abs(word.length() - candidate.length()) > most) {
            return false;
        }
        return distance(word, candidate) <= most;
    }

    /** The optimal string alignment distance between two words. */
    public static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int change = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + change);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}

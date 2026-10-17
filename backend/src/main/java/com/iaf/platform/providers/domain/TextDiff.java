package com.iaf.platform.providers.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * A small unified diff of two texts, for the change request a publish opens. Line based, longest
 * common subsequence; good for config files of a few hundred lines, which is what providers are.
 */
public final class TextDiff {

    private TextDiff() {
    }

    /** {@code path} names the file in the {@code ---}/{@code +++} header; {@code before} null means a new file. */
    public static String unified(String before, String after, String path) {
        List<String> a = before == null || before.isEmpty() ? List.of() : List.of(before.split("\n", -1));
        List<String> b = after == null || after.isEmpty() ? List.of() : List.of(after.split("\n", -1));
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(before == null ? "/dev/null" : "a/" + path).append('\n');
        out.append("+++ b/").append(path).append('\n');
        if (a.equals(b)) {
            return out.toString();
        }
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> lines = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                lines.add(" " + a.get(i));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                lines.add("-" + a.get(i++));
            } else {
                lines.add("+" + b.get(j++));
            }
        }
        while (i < a.size()) {
            lines.add("-" + a.get(i++));
        }
        while (j < b.size()) {
            lines.add("+" + b.get(j++));
        }
        out.append("@@ -1,").append(a.size()).append(" +1,").append(b.size()).append(" @@\n");
        for (String l : lines) {
            out.append(l).append('\n');
        }
        return out.toString();
    }
}

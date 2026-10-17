package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A small line diff in unified format (three lines of context), computed with a longest common
 * subsequence. Used by change requests to show what a version changes against the published one.
 */
public final class UnifiedDiff {

    private static final int CONTEXT = 3;
    /** Above this many DP cells the diff degrades to "everything removed, everything added". */
    private static final long MAX_CELLS = 4_000_000L;

    private UnifiedDiff() {
    }

    /** Lines added and removed by a diff. */
    public record Stats(int added, int removed) {
        public boolean unchanged() {
            return added == 0 && removed == 0;
        }

        @Override
        public String toString() {
            return "+" + added + " -" + removed;
        }
    }

    private enum Op {
        EQUAL, DELETE, INSERT
    }

    private record Edit(Op op, String line) {
    }

    /** The unified diff of two texts, or an empty string when they are equal. */
    public static String of(String before, String after, String path) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        List<Edit> edits = edits(a, b);
        if (edits.stream().allMatch(e -> e.op() == Op.EQUAL)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(a.isEmpty() ? "/dev/null" : "a/" + path).append('\n');
        sb.append("+++ ").append(b.isEmpty() ? "/dev/null" : "b/" + path).append('\n');
        hunks(edits, sb);
        return sb.toString();
    }

    /** Count the {@code +} and {@code -} lines of a unified diff (headers excluded). */
    public static Stats stats(String diff) {
        int added = 0;
        int removed = 0;
        if (diff != null) {
            for (String line : diff.split("\n")) {
                if (line.startsWith("+++") || line.startsWith("---")) {
                    continue;
                }
                if (line.startsWith("+")) {
                    added++;
                } else if (line.startsWith("-")) {
                    removed++;
                }
            }
        }
        return new Stats(added, removed);
    }

    static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        if (text.endsWith("\n")) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static List<Edit> edits(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        List<Edit> out = new ArrayList<>();
        if ((long) n * m > MAX_CELLS) {
            a.forEach(l -> out.add(new Edit(Op.DELETE, l)));
            b.forEach(l -> out.add(new Edit(Op.INSERT, l)));
            return out;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                out.add(new Edit(Op.EQUAL, a.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Edit(Op.DELETE, a.get(i)));
                i++;
            } else {
                out.add(new Edit(Op.INSERT, b.get(j)));
                j++;
            }
        }
        while (i < n) {
            out.add(new Edit(Op.DELETE, a.get(i++)));
        }
        while (j < m) {
            out.add(new Edit(Op.INSERT, b.get(j++)));
        }
        return out;
    }

    private static void hunks(List<Edit> edits, StringBuilder sb) {
        int n = edits.size();
        int[] oldNo = new int[n + 1];
        int[] newNo = new int[n + 1];
        int o = 1;
        int w = 1;
        for (int i = 0; i < n; i++) {
            oldNo[i] = o;
            newNo[i] = w;
            Edit e = edits.get(i);
            if (e.op() != Op.INSERT) {
                o++;
            }
            if (e.op() != Op.DELETE) {
                w++;
            }
        }
        oldNo[n] = o;
        newNo[n] = w;
        int i = 0;
        while (i < n) {
            if (edits.get(i).op() == Op.EQUAL) {
                i++;
                continue;
            }
            int start = Math.max(0, i - CONTEXT);
            int end = i;
            int j = i;
            while (j < n) {
                if (edits.get(j).op() != Op.EQUAL) {
                    end = j + 1;
                    j++;
                    continue;
                }
                int k = j;
                while (k < n && edits.get(k).op() == Op.EQUAL) {
                    k++;
                }
                if (k < n && k - j <= 2 * CONTEXT) {
                    j = k;
                } else {
                    break;
                }
            }
            int stop = Math.min(n, end + CONTEXT);
            int oldStart = oldNo[start];
            int newStart = newNo[start];
            int oldLen = oldNo[stop] - oldStart;
            int newLen = newNo[stop] - newStart;
            sb.append("@@ -").append(range(oldStart, oldLen)).append(" +").append(range(newStart, newLen)).append(" @@\n");
            for (int x = start; x < stop; x++) {
                Edit e = edits.get(x);
                char c = switch (e.op()) {
                    case EQUAL -> ' ';
                    case DELETE -> '-';
                    case INSERT -> '+';
                };
                sb.append(c).append(e.line()).append('\n');
            }
            i = stop;
        }
    }

    private static String range(int start, int len) {
        if (len == 0) {
            return (start - 1) + ",0";
        }
        return len == 1 ? String.valueOf(start) : start + "," + len;
    }
}

package com.iaf.console.drift.load;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * What the platform was asked, by minute of the run: requests of each kind, the provider calls behind
 * them (a page is one provider list call, a batch one provider read per resource in it), the 429s, and
 * per group the walks of its {@code :observe-list} pages started (a page without a cursor) and
 * finished (the last page), by hour.
 */
final class PlatformCalls {

    static final int PAGES = 0;
    static final int PAGE_ITEMS = 1;
    static final int BATCHES = 2;
    static final int BATCH_ITEMS = 3;
    static final int SINGLES = 4;
    static final int DISCOVER_PAGES = 5;
    static final int REFUSED = 6;
    private static final int KINDS = 7;

    private final Instant start;
    private final long[][] byMinute;
    private final int[][] walksStarted;
    private final int[][] walksFinished;
    private final Map<String, int[]> discoverWalks = new HashMap<>();
    private final Map<String, int[]> listWalks = new HashMap<>();

    PlatformCalls(Instant start, int hours, int groups) {
        this.start = start;
        this.byMinute = new long[hours * 60 + 60][KINDS];
        this.walksStarted = new int[hours + 1][groups];
        this.walksFinished = new int[hours + 1][groups];
    }

    void page(Estate.Group g, boolean first, boolean last, int items, Instant at) {
        long[] m = minute(at);
        m[PAGES]++;
        m[PAGE_ITEMS] += items;
        if (g != null) {
            int hour = hour(at);
            if (first) {
                walksStarted[hour][g.index()]++;
            }
            if (last) {
                walksFinished[hour][g.index()]++;
            }
        }
    }

    /** A page of a type's list in an environment, every account's resources in it. */
    void listPage(String list, boolean first, boolean last, int items, Instant at) {
        page(null, first, last, items, at);
        int[] walks = listWalks.computeIfAbsent(list, k -> new int[2]);
        if (first) {
            walks[0]++;
        }
        if (last) {
            walks[1]++;
        }
    }

    void discoverPage(String list, boolean first, boolean last, int items, Instant at) {
        minute(at)[DISCOVER_PAGES]++;
        int[] walks = discoverWalks.computeIfAbsent(list, k -> new int[2]);
        if (first) {
            walks[0]++;
        }
        if (last) {
            walks[1]++;
        }
    }

    void batch(int items, Instant at) {
        long[] m = minute(at);
        m[BATCHES]++;
        m[BATCH_ITEMS] += items;
    }

    void single(Instant at) {
        minute(at)[SINGLES]++;
    }

    void refused(Instant at) {
        minute(at)[REFUSED]++;
    }

    long count(int minute, int kind) {
        return minute < byMinute.length ? byMinute[minute][kind] : 0;
    }

    /** Requests the platform took in the minute (a 429 is not one). */
    long requests(int minute) {
        return count(minute, PAGES) + count(minute, BATCHES) + count(minute, SINGLES) + count(minute, DISCOVER_PAGES);
    }

    /** What the provider's API saw in the minute. */
    long providerCalls(int minute) {
        return count(minute, PAGES) + count(minute, BATCH_ITEMS) + count(minute, SINGLES) + count(minute, DISCOVER_PAGES);
    }

    int[] walksStarted(int hour) {
        return walksStarted[hour];
    }

    int[] walksFinished(int hour) {
        return walksFinished[hour];
    }

    Map<String, int[]> listWalks() {
        return listWalks;
    }

    Map<String, int[]> discoverWalks() {
        return discoverWalks;
    }

    private long[] minute(Instant at) {
        int m = (int) Math.min(byMinute.length - 1, Math.max(0, Duration.between(start, at).toMinutes()));
        return byMinute[m];
    }

    private int hour(Instant at) {
        return (int) Math.min(walksStarted.length - 1, Math.max(0, Duration.between(start, at).toHours()));
    }
}

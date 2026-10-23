package com.iaf.console.drift.load;

import com.iaf.console.drift.drift.domain.Tier;

/**
 * Every read a save records, per tier, by how long after the resource's previous read it came: in
 * under half its tier's interval (read again early, what a walk does to resources it meets before
 * they are due), within the interval, or later (overdue: the plan's guarantee missed).
 */
final class ReadStats {

    static final int EARLY = 0;
    static final int ON_TIME = 1;
    static final int OVERDUE = 2;

    private final long[][] counts = new long[Tier.values().length][3];
    private final long[] worst = new long[Tier.values().length];

    void read(Tier tier, long gapMillis, long intervalMillis) {
        int bucket = gapMillis * 2 < intervalMillis ? EARLY : gapMillis <= intervalMillis ? ON_TIME : OVERDUE;
        counts[tier.ordinal()][bucket]++;
        worst[tier.ordinal()] = Math.max(worst[tier.ordinal()], gapMillis);
    }

    long count(Tier tier, int bucket) {
        return counts[tier.ordinal()][bucket];
    }

    long reads(Tier tier) {
        long[] c = counts[tier.ordinal()];
        return c[EARLY] + c[ON_TIME] + c[OVERDUE];
    }

    /** The longest time between two reads of one resource of the tier. */
    long worstGapMillis(Tier tier) {
        return worst[tier.ordinal()];
    }

    ReadStats copy() {
        ReadStats out = new ReadStats();
        for (int t = 0; t < counts.length; t++) {
            out.counts[t] = counts[t].clone();
            out.worst[t] = worst[t];
        }
        return out;
    }

    /** What happened since {@code earlier}, a copy taken before; the worst gap is the whole run's. */
    ReadStats since(ReadStats earlier) {
        ReadStats out = copy();
        for (int t = 0; t < counts.length; t++) {
            for (int b = 0; b < 3; b++) {
                out.counts[t][b] -= earlier.counts[t][b];
            }
        }
        return out;
    }
}

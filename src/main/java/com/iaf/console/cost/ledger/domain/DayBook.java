package com.iaf.console.cost.ledger.domain;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closes days: from the rows a day opened with (the previous day's closing) and the day's deltas,
 * each bucket's cost that day and the rate, charged and unpriced counts it closed with. The same
 * function gives the open days a report shows (today up to now), so closed and open numbers are
 * computed one way. Deterministic: the same opening and deltas always give the same rows.
 */
public final class DayBook {

    private DayBook() {
    }

    /**
     * One day of one account.
     *
     * @param opening the rows of the day before (only their closing values are read)
     * @param deltas  the deltas whose instant falls on {@code day}, in any order
     * @param until   the end of the day, or now for a day still open
     */
    public static List<BucketDay> close(String accountId, LocalDate day, List<BucketDay> opening, List<RateDelta> deltas, Instant until) {
        Instant start = Accrual.startOf(day);
        Instant end = Accrual.startOf(day.plusDays(1));
        Instant stop = until.isAfter(end) ? end : until.isBefore(start) ? start : until;
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        Map<String, long[]> state = new LinkedHashMap<>();
        for (BucketDay o : opening) {
            buckets.put(o.bucket().id(), o.bucket());
            state.put(o.bucket().id(), new long[] { o.closingMicros(), o.resources(), o.unpriced() });
        }
        Map<String, List<RateDelta>> byBucket = new LinkedHashMap<>();
        for (RateDelta d : deltas) {
            buckets.putIfAbsent(d.bucket().id(), d.bucket());
            byBucket.computeIfAbsent(d.bucket().id(), k -> new ArrayList<>()).add(d);
        }
        List<BucketDay> out = new ArrayList<>();
        for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
            long[] s = state.getOrDefault(e.getKey(), new long[3]);
            long openingRate = s[0];
            long rate = s[0];
            long resources = s[1];
            long unpriced = s[2];
            BigInteger accrued = BigInteger.ZERO;
            Instant t = start;
            List<RateDelta> ds = new ArrayList<>(byBucket.getOrDefault(e.getKey(), List.of()));
            ds.sort(Comparator.comparing(RateDelta::at).thenComparing(RateDelta::id));
            for (RateDelta d : ds) {
                Instant at = d.at().isBefore(start) ? start : d.at();
                if (at.isAfter(stop)) {
                    // a delta after "now" on an open day: it starts counting when its instant comes
                    continue;
                }
                accrued = accrued.add(Accrual.numerator(rate, at.toEpochMilli() - t.toEpochMilli()));
                rate += d.monthlyMicros();
                resources += d.resources();
                unpriced += d.unpriced();
                t = at;
            }
            accrued = accrued.add(Accrual.numerator(rate, stop.toEpochMilli() - t.toEpochMilli()));
            BucketDay row = new BucketDay(accountId, day, e.getValue(), openingRate, rate, Accrual.toMicros(accrued, day), (int) resources, (int) unpriced);
            if (!row.empty()) {
                out.add(row);
            }
        }
        return out;
    }
}

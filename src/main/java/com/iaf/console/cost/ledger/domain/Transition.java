package com.iaf.console.cost.ledger.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.iaf.console.cost.pricing.domain.Rate;

/**
 * The pure rule of the ledger: from what a resource added to the ledger before ({@code before},
 * absent the first time) and what it is now (its snapshot and its rate), the record to store and
 * the deltas that move the buckets from the old contribution to the new one at the instant of the
 * change. A snapshot older than the version already priced changes nothing; so does one that
 * prices the same in the same bucket. The instant never goes back before the current rate's start,
 * so a resource's periods stay in order.
 */
public final class Transition {

    private Transition() {
    }

    /** The outcome: the record to store and the deltas to write; no deltas when only the version moved. */
    public record Outcome(RateRecord record, List<RateDelta> deltas) {
    }

    /**
     * @param force re-price even at the version already priced (a price list changed, a sync)
     */
    public static Optional<Outcome> apply(Optional<RateRecord> before, ResourceSnapshot now, Rate rate, Instant at, boolean force) {
        if (before.isPresent() && !force && now.version() <= before.get().stateVersion()) {
            return Optional.empty();
        }
        Bucket bucket = now.tombstone() && before.isPresent() ? before.get().contribution().bucket() : Bucket.of(now);
        boolean charged = now.charged();
        Contribution next = charged ? new Contribution(bucket, rate.monthlyMicros(), 1, rate.priced() ? 0 : 1) : Contribution.none(bucket);
        Contribution prev = before.map(RateRecord::contribution).orElse(null);
        Instant when = at;
        if (before.isPresent() && before.get().since() != null && when.isBefore(before.get().since())) {
            when = before.get().since();
        }
        long rev = before.map(RateRecord::rev).orElse(0L) + 1;
        List<RateDelta> deltas = deltas(now.wfrn(), rev, prev, next, when);
        boolean rateMoved = prev == null || prev.monthlyMicros() != next.monthlyMicros() || prev.resources() != next.resources();
        List<RateRecord.Period> history = history(before, next, when, rateMoved);
        Instant since = rateMoved || before.isEmpty() ? when : before.get().since();
        long version = Math.max(now.version(), before.map(RateRecord::stateVersion).orElse(0L));
        RateRecord record = new RateRecord(now.wfrn(), bucket.accountId(), now.type() != null ? now.type() : before.map(RateRecord::type).orElse(null),
                now.state(), version, rev, next, rate.currency(), charged ? rate.rule() : before.map(RateRecord::rule).orElse(rate.rule()),
                charged ? rate.pricedFrom() : null, since, history, deltas);
        return Optional.of(new Outcome(record, deltas));
    }

    private static List<RateDelta> deltas(String wfrn, long rev, Contribution prev, Contribution next, Instant at) {
        List<RateDelta> out = new ArrayList<>();
        String base = wfrn + "#" + rev + ":";
        if (prev != null && prev.bucket().id().equals(next.bucket().id())) {
            RateDelta d = new RateDelta(base + 0, next.bucket(), at, next.monthlyMicros() - prev.monthlyMicros(), next.resources() - prev.resources(),
                    next.unpriced() - prev.unpriced());
            if (!d.empty()) {
                out.add(d);
            }
            return out;
        }
        if (prev != null && !prev.nothing()) {
            out.add(new RateDelta(base + 0, prev.bucket(), at, -prev.monthlyMicros(), -prev.resources(), -prev.unpriced()));
        }
        if (!next.nothing()) {
            out.add(new RateDelta(base + 1, next.bucket(), at, next.monthlyMicros(), next.resources(), next.unpriced()));
        }
        return out;
    }

    private static List<RateRecord.Period> history(Optional<RateRecord> before, Contribution next, Instant at, boolean moved) {
        List<RateRecord.Period> old = before.map(RateRecord::history).orElse(List.of());
        if (!moved) {
            return old;
        }
        List<RateRecord.Period> out = new ArrayList<>();
        if (next.resources() > 0) {
            out.add(new RateRecord.Period(at, null, next.monthlyMicros()));
        }
        for (RateRecord.Period p : old) {
            out.add(p.to() == null ? new RateRecord.Period(p.from(), at, p.monthlyMicros()) : p);
            if (out.size() >= RateRecord.HISTORY) {
                break;
            }
        }
        return out;
    }
}

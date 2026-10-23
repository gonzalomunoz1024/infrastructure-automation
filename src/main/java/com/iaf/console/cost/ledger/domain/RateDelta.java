package com.iaf.console.cost.ledger.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A change of a bucket's monthly rate at an instant: signed micros, and the signed change of how
 * many charged and unpriced resources it holds. A resource's change of price is one delta, or two
 * when it moves between buckets (its tags changed). Deltas are only ever added, never changed, and
 * their sum is order-free, so a late delta just re-closes its day. The id is deterministic, so
 * writing one twice is harmless.
 */
public record RateDelta(String id, Bucket bucket, Instant at, long monthlyMicros, int resources, int unpriced) {

    public RateDelta {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(at, "at");
    }

    public String accountId() {
        return bucket.accountId();
    }

    /** The UTC day it falls on. */
    public LocalDate day() {
        return at.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    public boolean empty() {
        return monthlyMicros == 0 && resources == 0 && unpriced == 0;
    }
}

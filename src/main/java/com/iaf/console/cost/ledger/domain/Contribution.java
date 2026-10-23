package com.iaf.console.cost.ledger.domain;

/** What one resource adds to its bucket: its monthly rate, one charged resource, and one unpriced when no price covered it. */
public record Contribution(Bucket bucket, long monthlyMicros, int resources, int unpriced) {

    public static Contribution none(Bucket bucket) {
        return new Contribution(bucket, 0, 0, 0);
    }

    public boolean nothing() {
        return monthlyMicros == 0 && resources == 0 && unpriced == 0;
    }
}

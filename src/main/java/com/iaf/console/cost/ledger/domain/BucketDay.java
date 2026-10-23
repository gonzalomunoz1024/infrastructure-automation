package com.iaf.console.cost.ledger.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One bucket on one closed day: the rate it opened and closed the day with, what it cost that day,
 * and how many charged and unpriced resources it held at the end. Tomorrow opens where today closed.
 */
public record BucketDay(String accountId, LocalDate day, Bucket bucket, long openingMicros, long closingMicros, long amountMicros,
        int resources, int unpriced) {

    public BucketDay {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(bucket, "bucket");
    }

    public String id() {
        return accountId + ":" + day + ":" + bucket.id();
    }

    /** Nothing opened, closed, cost or remains: not worth storing. */
    public boolean empty() {
        return openingMicros == 0 && closingMicros == 0 && amountMicros == 0 && resources == 0 && unpriced == 0;
    }
}

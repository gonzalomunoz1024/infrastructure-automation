package com.iaf.console.cost.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.cost.pricing.domain.PriceRule;

/**
 * The ledger's view of one resource: what it adds to which bucket now, the price behind it, the
 * state version it was priced at, the rates it had (newest first, at most a year and a month), and
 * the deltas of its last change until they are known to be stored ({@code pending}): the record is
 * saved first with them, by compare-and-set on {@code rev}, and they are written after, so a crash
 * in between is mended by writing them again, never by guessing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RateRecord(String wfrn, String accountId, String type, String state, long stateVersion, long rev, Contribution contribution,
        String currency, PriceRule rule, String pricedFrom, Instant since, List<Period> history, List<RateDelta> pending) {

    /** The most rates a record remembers. */
    public static final int HISTORY = 50;

    public RateRecord {
        Objects.requireNonNull(wfrn, "wfrn");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(contribution, "contribution");
        history = history == null ? List.of() : List.copyOf(history);
        pending = pending == null ? List.of() : List.copyOf(pending);
    }

    /** One rate the resource had: from when, until when (open while current), how much a month. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Period(Instant from, Instant to, long monthlyMicros) {
    }

    public boolean charged() {
        return contribution.resources() > 0;
    }

    public RateRecord flushed() {
        return new RateRecord(wfrn, accountId, type, state, stateVersion, rev, contribution, currency, rule, pricedFrom, since, history, List.of());
    }
}

package com.iaf.console.cost.ledger.ports.outbound;

import java.time.LocalDate;
import java.util.List;

import com.iaf.console.cost.ledger.domain.BucketDay;
import com.iaf.console.cost.ledger.domain.CostCell;
import com.iaf.console.cost.ledger.domain.CostFilter;
import com.iaf.console.cost.ledger.domain.Granularity;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The closed days, one row per bucket and day, and the sums reports read from them. */
public interface DayStorePort {

    /** Replaces everything stored for the account's day with these rows. */
    Mono<Void> replaceDay(String accountId, LocalDate day, List<BucketDay> rows);

    /** The rows of one closed day. */
    Flux<BucketDay> listDay(AccountScope scope, LocalDate day);

    /**
     * The sums of the closed days {@code [from, to)} that pass the filter, one per period and group:
     * the key is the bucket's value of {@code dimension} ({@code CostCell.NONE} without a dimension or
     * when the bucket lacks the tag).
     */
    Flux<CostCell> sum(AccountScope scope, LocalDate from, LocalDate to, Granularity granularity, String dimension, CostFilter filter);

    /** Removes the account's rows before a day (retention). */
    Mono<Long> deleteBefore(String accountId, LocalDate day);
}

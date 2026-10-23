package com.iaf.console.drift.scorecards.ports.outbound;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.iaf.console.drift.scorecards.domain.CountDelta;
import com.iaf.console.drift.scorecards.domain.Counts;
import com.iaf.console.drift.scorecards.domain.RuleKey;
import com.iaf.console.drift.scorecards.domain.ScoreSnapshot;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Each account's passing and failing counts per rule, kept up to date by deltas and recounted daily, and the daily snapshots. */
public interface ScoreStorePort {

    /** Adds the deltas to the account's counters (a counter never goes below zero). */
    Mono<Void> apply(String accountId, List<CountDelta> deltas);

    /** Replaces every counter of the account with a recount. */
    Mono<Void> replace(String accountId, Map<RuleKey, Counts> counts);

    Mono<Map<RuleKey, Counts>> countsOf(AccountScope scope);

    @GlobalRead("the heat map: the BFF names the accounts it decided the person may see")
    Mono<Map<String, Map<RuleKey, Counts>>> countsOfAccounts(Collection<String> accountIds);

    @GlobalRead("the daily recount walks every account that has counters")
    Flux<String> listAccounts();

    /** Records the snapshot of its account and day, replacing one of the same day. */
    Mono<Void> saveSnapshot(ScoreSnapshot snapshot);

    /** The account's snapshots from a day on, oldest first. */
    Flux<ScoreSnapshot> listSnapshots(AccountScope scope, LocalDate from);

    @GlobalRead("the daily recount asks each account's last snapshot")
    Mono<ScoreSnapshot> findLatestSnapshot(String accountId);
}

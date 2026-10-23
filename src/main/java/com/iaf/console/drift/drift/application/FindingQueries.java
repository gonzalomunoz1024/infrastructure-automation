package com.iaf.console.drift.drift.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.FindingQuery;
import com.iaf.console.drift.drift.domain.FindingStats;
import com.iaf.console.drift.drift.domain.FindingsSummary;
import com.iaf.console.drift.drift.ports.outbound.FindingStorePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The reads of the findings API: a page, one finding, the account's summary with its last scan
 * time, and the counts of a window for the executive home (WP 9.5).
 */
public final class FindingQueries {

    /** The window of the drift numbers when the caller names no start. */
    static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final FindingStorePort findings;
    private final WatchStorePort watches;
    private final Clock clock;

    public FindingQueries(FindingStorePort findings, WatchStorePort watches, Clock clock) {
        this.findings = findings;
        this.watches = watches;
        this.clock = clock;
    }

    public Mono<Page<Finding>> list(AccountScope scope, FindingQuery query) {
        return findings.list(scope, query);
    }

    public Mono<Finding> get(AccountScope scope, String findingId) {
        return findings.find(scope, findingId).switchIfEmpty(Mono.error(() -> NotFoundException.of("finding", findingId)));
    }

    /** For the console's own services, which name no account: the finding wherever it is. */
    public Mono<Finding> getAny(String findingId) {
        return findings.findAny(findingId).switchIfEmpty(Mono.error(() -> NotFoundException.of("finding", findingId)));
    }

    public Mono<FindingsSummary> summary(AccountScope scope, String region) {
        return findings.countOpen(scope, region)
                .flatMap(summary -> watches.findLastCheckedAt(scope).map(summary::withLastScanAt).defaultIfEmpty(summary));
    }

    /** The findings opened and closed in the window (the last 30 days by default), for one account, or for every account when scope is null. */
    public Mono<FindingStats> stats(AccountScope scope, Instant since, Instant until) {
        Instant to = until != null ? until : clock.instant();
        Instant from = since != null ? since : to.minus(DEFAULT_WINDOW);
        if (!from.isBefore(to)) {
            return Mono.error(new InvalidInputException("invalid window",
                    List.of(Problem.error("window.invalid", "since", "since is before until"))));
        }
        return scope == null ? findings.countWindowEveryAccount(from, to) : findings.countWindow(scope, from, to);
    }
}

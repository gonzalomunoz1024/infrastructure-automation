package com.iaf.console.drift.drift.application;

import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.FindingQuery;
import com.iaf.console.drift.drift.domain.FindingsSummary;
import com.iaf.console.drift.drift.ports.outbound.FindingStorePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** The reads of the findings API: a page, one finding, the account's summary with its last scan time. */
public final class FindingQueries {

    private final FindingStorePort findings;
    private final WatchStorePort watches;

    public FindingQueries(FindingStorePort findings, WatchStorePort watches) {
        this.findings = findings;
        this.watches = watches;
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
}

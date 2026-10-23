package com.iaf.console.drift.drift.ports.outbound;

import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.FindingQuery;
import com.iaf.console.drift.drift.domain.FindingsSummary;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The findings, open and closed, by account. */
public interface FindingStorePort {

    Mono<Finding> find(AccountScope scope, String findingId);

    Mono<Page<Finding>> list(AccountScope scope, FindingQuery query);

    Mono<FindingsSummary> countOpen(AccountScope scope, String region);

    /** The one open finding on a resource, when there is one. */
    @GlobalRead("the scanner looks a resource's finding up by its name, which carries the account")
    Mono<Finding> findOpenByWfrn(String wfrn);

    /** Every finding whose revert job is still running, across accounts. */
    @GlobalRead("the follower walks every running revert")
    Flux<Finding> findReverting(int limit);

    @GlobalRead("the console's own services name no account; the finding carries it")
    Mono<Finding> findAny(String findingId);

    Mono<Finding> save(Finding finding);
}

package com.iaf.console.bff.insights.ports.outbound;

import java.time.Instant;

import com.iaf.console.bff.insights.domain.DriftNumbers;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** The drift service's numbers for the executive home ({@code GET /v1/findings/stats}), read as the console-internal principal. */
public interface DriftStatsPort {

    /** One account's findings opened and closed in the window and open now; empty when the console runs without a drift service. */
    Mono<DriftNumbers> findStats(AccountScope scope, Instant since, Instant until);
}

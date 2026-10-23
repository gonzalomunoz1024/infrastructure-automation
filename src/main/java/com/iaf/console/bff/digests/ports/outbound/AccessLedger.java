package com.iaf.console.bff.digests.ports.outbound;

import java.time.Instant;
import java.util.List;

import com.iaf.console.bff.digests.domain.AccessFact;
import com.iaf.console.bff.digests.domain.SubjectState;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The weekly digest's record of the identity events the BFF follows (decision 32.4): the grants
 * each event showed, once per event key, and every subject's latest state, to tell what the next
 * event of it grants.
 */
public interface AccessLedger {

    /** A subject's latest state in the scope's account; empty when the BFF has seen none. */
    Mono<SubjectState> findState(AccountScope scope, String aggregate, String aggregateId);

    /**
     * Records one event: its facts, unless its key was recorded already, and the subject's state
     * after it, unless a newer one is held (null forgets the subject).
     */
    Mono<Void> record(String accountId, String eventKey, Instant at, List<AccessFact> facts, String aggregate, String aggregateId, SubjectState after);

    /** The facts of the scope's account at or after {@code from} and before {@code to}. */
    Mono<List<AccessFact>> list(AccountScope scope, Instant from, Instant to);

    /** Notes that the BFF follows the identity events from now, unless it noted an earlier start; when it began. */
    Mono<Instant> follow(Instant now);

    /** When the BFF began following the identity events; empty when it never did. */
    Mono<Instant> followingSince();
}

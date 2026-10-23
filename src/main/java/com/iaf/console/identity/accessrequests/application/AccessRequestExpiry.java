package com.iaf.console.identity.accessrequests.application;

import java.time.Clock;
import java.time.Instant;

import com.iaf.console.identity.accessrequests.domain.AccessRequest;
import com.iaf.console.identity.accessrequests.ports.outbound.AccessRequestStore;
import com.iaf.console.identity.accounts.ports.outbound.AccountClosurePort;
import com.iaf.console.identity.common.Paging;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Turns pending requests past their thirty days into expired ones, with the AccessRequestChanged
 * event the BFF notifies from; a closed account's pending requests expire when it closes.
 */
public final class AccessRequestExpiry implements AccountClosurePort {

    private final AccessRequestStore requests;
    private final IdentityEvents events;
    private final Clock clock;

    public AccessRequestExpiry(AccessRequestStore requests, IdentityEvents events, Clock clock) {
        this.requests = requests;
        this.events = events;
        this.clock = clock;
    }

    /** Expires what is due; answers how many requests it expired. */
    public Mono<Long> sweep() {
        Instant cutoff = clock.instant().minus(AccessRequest.PENDING_FOR);
        String actor = Caller.internal("console-identity-service", "global").actor();
        return requests.findPendingBefore(cutoff)
                .concatMap(r -> requests.update(r.expired(), r.version())
                        .flatMap(stored -> events.changed(IdentityEvents.Subject.ACCESS_REQUEST, stored.requestId(), stored.accountId(), actor, stored.version(),
                                "expired", stored).thenReturn(stored)))
                .count();
    }

    /** Every pending request of a closed account expires, each with its event; one decided meanwhile stays decided. */
    @Override
    public Mono<Void> accountClosed(AccountScope scope, String actor) {
        return pending(scope, null).collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(r -> requests.update(r.expired(), r.version())
                        .flatMap(stored -> events.changed(IdentityEvents.Subject.ACCESS_REQUEST, stored.requestId(), stored.accountId(), actor, stored.version(),
                                "expired", stored)))
                .then();
    }

    private Flux<AccessRequest> pending(AccountScope scope, String after) {
        return requests.list(scope, null, AccessRequest.Status.pending, Paging.MAX, after)
                .flatMapMany(page -> Flux.fromIterable(page.items()).concatWith(page.next() == null ? Flux.empty() : pending(scope, page.next())));
    }
}

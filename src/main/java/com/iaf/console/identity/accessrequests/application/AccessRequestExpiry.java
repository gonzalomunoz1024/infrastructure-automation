package com.iaf.console.identity.accessrequests.application;

import java.time.Clock;
import java.time.Instant;

import com.iaf.console.identity.accessrequests.domain.AccessRequest;
import com.iaf.console.identity.accessrequests.ports.outbound.AccessRequestStore;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;

import reactor.core.publisher.Mono;

/** Turns pending requests past their thirty days into expired ones, with the AccessRequestChanged event the BFF notifies from. */
public final class AccessRequestExpiry {

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
}

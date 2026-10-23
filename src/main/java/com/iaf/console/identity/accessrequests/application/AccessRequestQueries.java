package com.iaf.console.identity.accessrequests.application;

import java.time.Clock;

import java.util.Optional;
import com.iaf.console.identity.common.web.DeniedException;
import com.iaf.console.identity.accessrequests.domain.AccessRequest;
import com.iaf.console.identity.accessrequests.ports.outbound.AccessRequestStore;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** The reads on access requests: a person sees their own; an account admin or the internal principal every request of the account. */
public final class AccessRequestQueries {

    private static final String KIND = "access-request";

    private final AccessRequestStore requests;
    private final AccessGuard guard;
    private final IamSupport support;
    private final Clock clock;

    public AccessRequestQueries(AccessRequestStore requests, AccessGuard guard, IamSupport support, Clock clock) {
        this.requests = requests;
        this.guard = guard;
        this.support = support;
        this.clock = clock;
    }

    public Mono<AccessRequest> get(Caller caller, String accountId, String requestId) {
        return support.scope(caller, accountId).flatMap(scope -> requests.find(scope, requestId)
                .switchIfEmpty(Mono.error(NotFoundException.of("access request", requestId)))
                .flatMap(r -> {
                    boolean mine = caller.isUser() && caller.id().equals(r.requester().userId());
                    Mono<Void> allowed = mine ? Mono.empty() : guard.require(caller, accountId, "access-request:get", support.resource(accountId, KIND, requestId));
                    return allowed.thenReturn(r.served(clock.instant()));
                }));
    }

    public Mono<Page<AccessRequest>> list(Caller caller, String accountId, String status, String requester, int limit, String after) {
        return support.scope(caller, accountId).flatMap(scope -> {
            AccessRequest.Status wanted = status == null ? null : parse(status);
            boolean own = caller.isUser() && (requester == null || requester.equals(caller.id()));
            // whoever may list the account's requests sees them all (filtered by requester when asked); anyone else their own, nobody else's
            Mono<Optional<String>> filter = caller.isInternal() ? Mono.just(Optional.ofNullable(requester))
                    : guard.require(caller, accountId, "access-request:list", support.anyResource(accountId, KIND)).thenReturn(Optional.ofNullable(requester))
                            .onErrorResume(DeniedException.class, denied -> own ? Mono.just(Optional.of(caller.id())) : Mono.error(denied));
            return filter.flatMap(f -> requests.list(scope, f.orElse(null), wanted, limit, after))
                    .map(page -> new Page<>(page.items().stream().map(r -> r.served(clock.instant()))
                            .filter(r -> wanted == null || r.status() == wanted).toList(), page.next()));
        });
    }

    private static AccessRequest.Status parse(String status) {
        try {
            return AccessRequest.Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status is pending, approved, rejected or expired");
        }
    }
}

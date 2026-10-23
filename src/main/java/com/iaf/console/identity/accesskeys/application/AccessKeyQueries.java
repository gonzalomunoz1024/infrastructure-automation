package com.iaf.console.identity.accesskeys.application;

import java.time.Clock;

import com.iaf.console.identity.accesskeys.domain.AccessKeyView;
import com.iaf.console.identity.accesskeys.ports.outbound.AccessKeyStore;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** The reads on access keys: a user sees their own keys; other users' keys take {@code iam:get} or {@code iam:list}. */
public final class AccessKeyQueries {

    private static final String KIND = "access-key";

    private final AccessKeyStore keys;
    private final AccessGuard guard;
    private final IamSupport support;
    private final Clock clock;

    public AccessKeyQueries(AccessKeyStore keys, AccessGuard guard, IamSupport support, Clock clock) {
        this.keys = keys;
        this.guard = guard;
        this.support = support;
        this.clock = clock;
    }

    public Mono<AccessKeyView> get(Caller caller, String accountId, String keyId) {
        return support.scope(caller, accountId).flatMap(scope -> keys.find(scope, keyId)
                .switchIfEmpty(Mono.error(NotFoundException.of("access key", keyId)))
                .flatMap(key -> {
                    Mono<Void> allowed = caller.isUser() && caller.id().equals(key.userId()) ? Mono.empty()
                            : guard.require(caller, accountId, "iam:get", support.resource(accountId, KIND, keyId));
                    return allowed.thenReturn(key.view(clock.instant()));
                }));
    }

    public Mono<Page<AccessKeyView>> list(Caller caller, String accountId, String userId, int limit, String after) {
        return support.scope(caller, accountId).flatMap(scope -> {
            boolean own = caller.isUser() && userId != null && caller.id().equals(userId);
            Mono<Void> allowed = own ? Mono.empty() : guard.require(caller, accountId, "iam:list", support.anyResource(accountId, KIND));
            return allowed.then(keys.list(scope, userId, limit, after)).map(page -> page.map(k -> k.view(clock.instant())));
        });
    }
}

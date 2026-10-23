package com.iaf.console.identity.quotas.application;

import java.time.Clock;

import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.quotas.domain.Quota;
import com.iaf.console.identity.quotas.domain.QuotaKeys;
import com.iaf.console.identity.quotas.domain.QuotaOverride;
import com.iaf.console.identity.quotas.domain.QuotaRequest;
import com.iaf.console.identity.quotas.ports.outbound.PlatformQuotaPort;
import com.iaf.console.identity.quotas.ports.outbound.QuotaStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The writes on quotas. Setting a limit is the internal principal's (the platform team's
 * decision); a {@code jobs-per-minute} limit is also written to the platform's bucket (P-10), and
 * an open request for the key is approved when the new limit reaches what it asked. Any member
 * with {@code quota:request-increase} opens a request; one open request per key.
 */
public final class QuotaCommands {

    private static final String KIND = "quota";

    private final QuotaStore quotas;
    private final PlatformQuotaPort platform;
    private final QuotaQueries queries;
    private final AccessGuard guard;
    private final IdentityEvents events;
    private final IamSupport support;
    private final Ids ids;
    private final Clock clock;

    public QuotaCommands(QuotaStore quotas, PlatformQuotaPort platform, QuotaQueries queries, AccessGuard guard, IdentityEvents events, IamSupport support,
            Ids ids, Clock clock) {
        this.quotas = quotas;
        this.platform = platform;
        this.queries = queries;
        this.guard = guard;
        this.events = events;
        this.support = support;
        this.ids = ids;
        this.clock = clock;
    }

    public Mono<Quota> set(Caller caller, String accountId, String key, SetQuota input) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException("no such operation"));
        }
        if (!QuotaKeys.isValid(key)) {
            return Mono.error(NotFoundException.of("quota", key));
        }
        new Names.Check().require(input.limit() != null && input.limit() >= 0, "limit", "limit is an integer of at least 0")
                .require(input.burst() == null || input.burst() >= 0, "burst", "burst is an integer of at least 0").done("the quota");
        AccountScope scope = caller.scope(accountId);
        Mono<Void> toPlatform = QuotaKeys.isPlatform(key)
                ? platform.putBucket(accountId, input.limit(), input.burst() == null ? input.limit() : input.burst()).then() : Mono.empty();
        return toPlatform
                .then(quotas.findOverride(scope, key).map(o -> o.changed(input.limit(), input.burst(), clock.instant()))
                        .defaultIfEmpty(new QuotaOverride(accountId, key, input.limit(), input.burst(), 1, clock.instant())))
                .flatMap(quotas::upsert)
                .flatMap(stored -> quotas.findOpenRequest(scope, key)
                        .filter(r -> r.limit() <= stored.limit())
                        .flatMap(r -> quotas.updateRequest(r.decided(QuotaRequest.Status.approved, caller.actor(), clock.instant()), r.version()))
                        .then(queries.quota(scope, key, stored))
                        .flatMap(q -> events.changed(IdentityEvents.Subject.QUOTA, key, accountId, caller.actor(), stored.version(), "updated", q)
                                .thenReturn(q)));
    }

    public Mono<IncreaseAccepted> requestIncrease(Caller caller, String accountId, String key, RequestIncrease input) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "quota:request-increase", support.resource(accountId, KIND, key)).thenReturn(scope))
                .flatMap(scope -> {
                    if (!QuotaKeys.isValid(key)) {
                        return Mono.error(NotFoundException.of("quota", key));
                    }
                    new Names.Check().require(input.limit() != null && input.limit() >= 1, "limit", "limit is an integer of at least 1")
                            .require(input.reason() != null && input.reason().length() >= 10 && input.reason().length() <= 1000, "reason",
                                    "reason is 10 to 1000 characters")
                            .done("the request");
                    return quotas.findOpenRequest(scope, key)
                            .flatMap(open -> Mono.<QuotaRequest>error(new ConflictException("an increase of " + key + " is already requested",
                                    java.util.Map.of("requestId", open.requestId()))))
                            .switchIfEmpty(Mono.defer(() -> quotas.insertRequest(QuotaRequest.open(ids.quotaRequest(), accountId, key, input.limit(),
                                    input.reason(), caller.actor(), clock.instant()))))
                            .flatMap(r -> events.changed(IdentityEvents.Subject.QUOTA, key, accountId, caller.actor(), r.version(), "updated",
                                    java.util.Map.of("key", key, "pendingRequestId", r.requestId(), "requestedLimit", r.limit())).thenReturn(r))
                            .map(r -> new IncreaseAccepted(r.requestId(), r.status().name()));
                });
    }

    /** The platform team denies an open request (internal principal); beyond the contract, listed in the README. */
    public Mono<QuotaRequest> deny(Caller caller, String accountId, String requestId) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException("no such operation"));
        }
        AccountScope scope = caller.scope(accountId);
        return quotas.findRequest(scope, requestId)
                .switchIfEmpty(Mono.error(NotFoundException.of("quota request", requestId)))
                .flatMap(r -> r.status() != QuotaRequest.Status.open ? Mono.error(new ConflictException("the request is " + r.status()))
                        : quotas.updateRequest(r.decided(QuotaRequest.Status.denied, caller.actor(), clock.instant()), r.version()))
                .flatMap(r -> events.changed(IdentityEvents.Subject.QUOTA, r.key(), accountId, caller.actor(), r.version(), "updated",
                        java.util.Map.of("key", r.key(), "deniedRequestId", r.requestId())).thenReturn(r));
    }
}

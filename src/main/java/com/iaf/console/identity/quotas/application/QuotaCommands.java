package com.iaf.console.identity.quotas.application;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

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
 * The writes on quotas. Setting a limit and deciding an increase request are the internal
 * principal's (the platform team's); a {@code jobs-per-minute} limit is also written to the
 * platform's bucket (P-10). Any member with {@code quota:request-increase} opens a request, one
 * open request per key; the requester, an account admin ({@code quota:set}) or the internal
 * principal withdraws it. A limit set at or above an open request's ask approves it (it is moot);
 * a lower one leaves it open, since a rejection is a {@code :decide} that carries a comment. Every
 * change publishes a {@code QuotaChanged} with the quota as served and the request it was about.
 */
public final class QuotaCommands {

    private static final String KIND = "quota";
    private static final int MAX_COMMENT = 2000;

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
        return apply(scope, key, input.limit(), input.burst())
                .flatMap(stored -> quotas.findOpenRequest(scope, key)
                        .filter(open -> open.requestedLimit() <= stored.limit())
                        .flatMap(moot -> quotas.updateRequest(moot.approved(caller.actor(), stored.limit(), null, clock.instant()), moot.version()))
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(approved -> changed(scope, key, caller.actor(), approved.isPresent() ? "approved" : "updated", approved.orElse(null))));
    }

    /**
     * The platform team's decision on an open request. Approving sets the key's limit to the one
     * granted (the ask by default) as {@link #set} does, keeping an override's burst when it is at
     * least that limit; rejecting leaves the limit. Both record the comment; a request already
     * decided or withdrawn is a 409.
     */
    public Mono<QuotaRequest> decide(Caller caller, String accountId, String requestId, QuotaDecision input) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException("no such operation"));
        }
        boolean approve = "approve".equals(input.decision());
        new Names.Check().require(approve || "reject".equals(input.decision()), "decision", "decision is approve or reject")
                .require(input.limit() == null || input.limit() >= 1, "limit", "limit is an integer of at least 1")
                .require(input.comment() == null || input.comment().length() <= MAX_COMMENT, "comment", "comment is at most 2000 characters")
                .done("the decision");
        AccountScope scope = caller.scope(accountId);
        return quotas.findRequest(scope, requestId)
                .switchIfEmpty(Mono.error(NotFoundException.of("quota request", requestId)))
                .flatMap(request -> {
                    if (request.status() != QuotaRequest.Status.open) {
                        return Mono.error(new ConflictException("the request is " + request.status()));
                    }
                    String key = request.quotaKey();
                    if (!approve) {
                        return record(request, request.rejected(caller.actor(), input.comment(), clock.instant()))
                                .flatMap(rejected -> changed(scope, key, caller.actor(), "rejected", rejected).thenReturn(rejected));
                    }
                    int granted = input.limit() == null ? request.requestedLimit() : input.limit();
                    return quotas.findOverride(scope, key).map(Optional::of).defaultIfEmpty(Optional.empty())
                            .flatMap(override -> apply(scope, key, granted, keptBurst(override.orElse(null), granted)))
                            .then(record(request, request.approved(caller.actor(), granted, input.comment(), clock.instant())))
                            .flatMap(approved -> changed(scope, key, caller.actor(), "approved", approved).thenReturn(approved));
                });
    }

    public Mono<QuotaRequest> requestIncrease(Caller caller, String accountId, String key, RequestIncrease input) {
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
                                    Map.of("requestId", open.requestId()))))
                            .switchIfEmpty(Mono.defer(() -> served(scope, key)
                                    .flatMap(current -> quotas.insertRequest(QuotaRequest.open(ids.quotaRequest(), accountId, key, current.limit(),
                                            input.limit(), input.reason(), caller.actor(), clock.instant())))))
                            .flatMap(opened -> changed(scope, key, caller.actor(), "updated", opened).thenReturn(opened));
                });
    }

    /** The requester, an account admin ({@code quota:set}) or the internal principal withdraws an open request; a decided one is a 409. */
    public Mono<Void> withdraw(Caller caller, String accountId, String requestId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> quotas.findRequest(scope, requestId)
                        .switchIfEmpty(Mono.error(NotFoundException.of("quota request", requestId)))
                        .flatMap(request -> {
                            boolean mine = caller.isUser() && caller.id().equals(request.requestedBy());
                            Mono<Void> allowed = mine || caller.isInternal() ? Mono.empty()
                                    : guard.require(caller, accountId, "quota:set", support.resource(accountId, KIND, request.quotaKey()));
                            return allowed.then(Mono.defer(() -> request.status() != QuotaRequest.Status.open
                                    ? Mono.error(new ConflictException("the request is " + request.status()))
                                    : record(request, request.withdrawn(caller.actor(), clock.instant()))
                                            .flatMap(withdrawn -> changed(scope, withdrawn.quotaKey(), caller.actor(), "updated", withdrawn))
                                            .then()));
                        }));
    }

    /** Sets a key's limit: the platform's bucket first for {@code jobs-per-minute} (P-10), then the account's override. */
    private Mono<QuotaOverride> apply(AccountScope scope, String key, int limit, Integer burst) {
        String accountId = scope.accountId();
        Mono<Void> toPlatform = QuotaKeys.isPlatform(key) ? platform.putBucket(accountId, limit, burst == null ? limit : burst).then() : Mono.empty();
        return toPlatform
                .then(quotas.findOverride(scope, key).map(o -> o.changed(limit, burst, clock.instant()))
                        .defaultIfEmpty(new QuotaOverride(accountId, key, limit, burst, 1, clock.instant())))
                .flatMap(quotas::upsert);
    }

    /** An approval keeps the override's burst when it still covers the granted limit; otherwise the burst is the limit. */
    private static Integer keptBurst(QuotaOverride override, int granted) {
        return override != null && override.burst() != null && override.burst() >= granted ? override.burst() : null;
    }

    /** Stores a request's next state; a concurrent change of it is a 409. */
    private Mono<QuotaRequest> record(QuotaRequest before, QuotaRequest after) {
        return quotas.updateRequest(after, before.version()).switchIfEmpty(Mono.error(new ConflictException("the request moved on; read it again")));
    }

    /** The quota as served now. */
    private Mono<Quota> served(AccountScope scope, String key) {
        return quotas.findOverride(scope, key).map(Optional::of).defaultIfEmpty(Optional.empty())
                .flatMap(override -> queries.quota(scope, key, override.orElse(null)));
    }

    /** Publishes the change with the quota's next change version, and answers the quota as served. */
    private Mono<Quota> changed(AccountScope scope, String key, String actor, String action, QuotaRequest request) {
        return served(scope, key).flatMap(quota -> quotas.nextChangeVersion(scope, key)
                .flatMap(version -> events.changed(IdentityEvents.Subject.QUOTA, key, scope.accountId(), actor, version, action,
                        new QuotaSnapshot(quota, request)))
                .thenReturn(quota));
    }
}

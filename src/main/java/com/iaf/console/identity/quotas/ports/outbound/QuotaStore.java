package com.iaf.console.identity.quotas.ports.outbound;

import java.time.Instant;

import com.iaf.console.identity.quotas.domain.QuotaOverride;
import com.iaf.console.identity.quotas.domain.QuotaRequest;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where an account's quota overrides and increase requests live. */
public interface QuotaStore {

    Mono<QuotaOverride> upsert(QuotaOverride override);

    Mono<QuotaOverride> findOverride(AccountScope scope, String key);

    Flux<QuotaOverride> findOverrides(AccountScope scope);

    Mono<QuotaRequest> insertRequest(QuotaRequest request);

    Mono<QuotaRequest> updateRequest(QuotaRequest request, long expectedVersion);

    Mono<QuotaRequest> findRequest(AccountScope scope, String requestId);

    Mono<QuotaRequest> findOpenRequest(AccountScope scope, String key);

    Mono<Page<QuotaRequest>> listRequests(AccountScope scope, QuotaRequest.Status status, int limit, String after);

    /** When an auto-approval rule last approved an increase of the key in the account; empty when none has. */
    Mono<Instant> lastRuleApproval(AccountScope scope, String key);

    /**
     * The next version of one quota's change events, from 1: a counter per account and key, so every
     * {@code QuotaChanged} (a limit set, a request opened, decided or withdrawn) has its own event key
     * and the outbox never takes one for a replay of another.
     */
    Mono<Long> nextChangeVersion(AccountScope scope, String key);
}

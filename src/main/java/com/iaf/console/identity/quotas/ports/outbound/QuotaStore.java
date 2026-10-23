package com.iaf.console.identity.quotas.ports.outbound;

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
}

package com.iaf.console.identity.quotas.adapters.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

import com.iaf.console.identity.quotas.domain.QuotaOverride;
import com.iaf.console.identity.quotas.domain.QuotaRequest;
import com.iaf.console.identity.quotas.ports.outbound.QuotaStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory quotas of the demo and the tests. */
public final class InMemoryQuotaStore implements QuotaStore {

    private final Map<String, QuotaOverride> overrides = new ConcurrentSkipListMap<>();
    private final Map<String, QuotaRequest> requests = new ConcurrentSkipListMap<>();
    private final Map<String, AtomicLong> changeVersions = new ConcurrentHashMap<>();

    @Override
    public Mono<QuotaOverride> upsert(QuotaOverride o) {
        return Mono.fromSupplier(() -> {
            overrides.put(o.accountId() + ":" + o.key(), o);
            return o;
        });
    }

    @Override
    public Mono<QuotaOverride> findOverride(AccountScope scope, String key) {
        return Mono.justOrEmpty(overrides.get(scope.accountId() + ":" + key));
    }

    @Override
    public Flux<QuotaOverride> findOverrides(AccountScope scope) {
        return Flux.defer(() -> Flux.fromIterable(overrides.values().stream().filter(o -> o.accountId().equals(scope.accountId())).toList()));
    }

    @Override
    public Mono<QuotaRequest> insertRequest(QuotaRequest r) {
        return Mono.fromCallable(() -> {
            if (requests.putIfAbsent(r.requestId(), r) != null) {
                throw new ConflictException("the request id exists");
            }
            return r;
        });
    }

    @Override
    public Mono<QuotaRequest> updateRequest(QuotaRequest r, long expectedVersion) {
        return Mono.fromCallable(() -> {
            synchronized (requests) {
                QuotaRequest current = requests.get(r.requestId());
                if (current == null || current.version() != expectedVersion) {
                    return null;
                }
                requests.put(r.requestId(), r);
                return r;
            }
        });
    }

    @Override
    public Mono<QuotaRequest> findRequest(AccountScope scope, String requestId) {
        return Mono.justOrEmpty(requests.get(requestId)).filter(r -> r.accountId().equals(scope.accountId()));
    }

    @Override
    public Mono<QuotaRequest> findOpenRequest(AccountScope scope, String key) {
        return Mono.justOrEmpty(requests.values().stream()
                .filter(r -> r.accountId().equals(scope.accountId()) && r.quotaKey().equals(key) && r.status() == QuotaRequest.Status.open).findFirst());
    }

    @Override
    public Mono<Page<QuotaRequest>> listRequests(AccountScope scope, QuotaRequest.Status status, int limit, String after) {
        return Mono.fromSupplier(() -> {
            String afterId = after == null ? null : CursorPaging.decode(after).id();
            List<QuotaRequest> fetched = requests.values().stream()
                    .filter(r -> r.accountId().equals(scope.accountId()))
                    .filter(r -> status == null || r.status() == status)
                    .filter(r -> afterId == null || r.requestId().compareTo(afterId) > 0)
                    .limit(limit + 1L)
                    .toList();
            return Page.from(fetched, limit, r -> new Cursor(r.requestId(), r.requestId()));
        });
    }

    @Override
    public Mono<Instant> lastRuleApproval(AccountScope scope, String key) {
        return Mono.fromSupplier(() -> requests.values().stream()
                .filter(r -> r.accountId().equals(scope.accountId()) && r.quotaKey().equals(key) && r.approvedByRule())
                .map(QuotaRequest::decidedAt)
                .max(Comparator.naturalOrder())
                .orElse(null));
    }

    @Override
    public Mono<Long> nextChangeVersion(AccountScope scope, String key) {
        return Mono.fromSupplier(() -> changeVersions.computeIfAbsent(scope.accountId() + ":" + key, k -> new AtomicLong()).incrementAndGet());
    }
}

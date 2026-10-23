package com.iaf.console.identity.accessrequests.adapters.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import com.iaf.console.identity.accessrequests.domain.AccessRequest;
import com.iaf.console.identity.accessrequests.ports.outbound.AccessRequestStore;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory access requests of the demo and the tests. */
public final class InMemoryAccessRequestStore implements AccessRequestStore {

    private final Map<String, AccessRequest> requests = new ConcurrentSkipListMap<>();

    @Override
    public Mono<AccessRequest> insert(AccessRequest r) {
        return Mono.fromSupplier(() -> {
            requests.put(r.requestId(), r);
            return r;
        });
    }

    @Override
    public Mono<AccessRequest> update(AccessRequest r, long expectedVersion) {
        return Mono.fromCallable(() -> {
            synchronized (requests) {
                AccessRequest current = requests.get(r.requestId());
                if (current == null || current.version() != expectedVersion) {
                    return null;
                }
                requests.put(r.requestId(), r);
                return r;
            }
        });
    }

    @Override
    public Mono<AccessRequest> find(AccountScope scope, String requestId) {
        return Mono.justOrEmpty(requests.get(requestId)).filter(r -> r.accountId().equals(scope.accountId()));
    }

    @Override
    public Mono<AccessRequest> findPending(AccountScope scope, String userId, String role) {
        return Mono.justOrEmpty(requests.values().stream().filter(r -> r.accountId().equals(scope.accountId()) && r.requester().userId().equals(userId)
                && r.role().equals(role) && r.status() == AccessRequest.Status.pending).findFirst());
    }

    @Override
    public Flux<AccessRequest> findPendingBefore(Instant cutoff) {
        return Flux.fromStream(() -> requests.values().stream().filter(r -> r.status() == AccessRequest.Status.pending && r.requestedAt().isBefore(cutoff)));
    }

    @Override
    public Mono<Page<AccessRequest>> list(AccountScope scope, String requesterUserId, AccessRequest.Status status, int limit, String after) {
        return Mono.fromSupplier(() -> {
            String afterId = after == null ? null : CursorPaging.decode(after).id();
            List<AccessRequest> fetched = requests.values().stream()
                    .filter(r -> r.accountId().equals(scope.accountId()))
                    .filter(r -> requesterUserId == null || r.requester().userId().equals(requesterUserId))
                    .filter(r -> status == null || r.status() == status)
                    .filter(r -> afterId == null || r.requestId().compareTo(afterId) > 0)
                    .limit(limit + 1L)
                    .toList();
            return Page.from(fetched, limit, r -> new Cursor(r.requestId(), r.requestId()));
        });
    }
}

package com.iaf.console.identity.principals.adapters.memory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory memberships of the demo and the tests, keyed like the Mongo store. */
public final class InMemoryMembershipStore implements MembershipStore {

    private final Map<String, Membership> memberships = new ConcurrentSkipListMap<>();

    private static String id(String accountId, String userId) {
        return accountId + ":" + userId;
    }

    @Override
    public Mono<Membership> insert(Membership m) {
        return Mono.fromCallable(() -> {
            synchronized (memberships) {
                if (memberships.containsKey(id(m.accountId(), m.userId()))) {
                    throw new ConflictException("the user is already in the account");
                }
                memberships.put(id(m.accountId(), m.userId()), m);
                return m;
            }
        });
    }

    @Override
    public Mono<Membership> update(Membership m, long expectedVersion) {
        return Mono.fromCallable(() -> {
            synchronized (memberships) {
                Membership current = memberships.get(id(m.accountId(), m.userId()));
                if (current == null || current.version() != expectedVersion) {
                    return null;
                }
                memberships.put(id(m.accountId(), m.userId()), m);
                return m;
            }
        });
    }

    @Override
    public Mono<Boolean> delete(AccountScope scope, String userId) {
        return Mono.fromSupplier(() -> memberships.remove(id(scope.accountId(), userId)) != null);
    }

    @Override
    public Mono<Membership> find(AccountScope scope, String userId) {
        return Mono.justOrEmpty(memberships.get(id(scope.accountId(), userId)));
    }

    @Override
    public Mono<Page<Membership>> list(AccountScope scope, String query, int limit, String after) {
        return Mono.fromSupplier(() -> {
            String afterId = after == null ? null : CursorPaging.decode(after).id();
            String prefix = query == null || query.isBlank() ? null : query.trim().toLowerCase(Locale.ROOT);
            List<Membership> fetched = memberships.values().stream()
                    .filter(m -> m.accountId().equals(scope.accountId()))
                    .filter(m -> prefix == null || m.email().toLowerCase(Locale.ROOT).startsWith(prefix)
                            || m.name() != null && m.name().toLowerCase(Locale.ROOT).startsWith(prefix))
                    .filter(m -> afterId == null || m.userId().compareTo(afterId) > 0)
                    .sorted((a, b) -> a.userId().compareTo(b.userId()))
                    .limit(limit + 1L)
                    .toList();
            return Page.from(fetched, limit, m -> new Cursor(m.userId(), m.userId()));
        });
    }

    @Override
    public Flux<Membership> findAll(AccountScope scope) {
        return Flux.defer(() -> Flux.fromIterable(memberships.values().stream().filter(m -> m.accountId().equals(scope.accountId())).toList()));
    }

    @Override
    public Flux<Membership> findByGroup(AccountScope scope, String groupId) {
        return findAll(scope).filter(m -> m.inGroup(groupId));
    }

    @Override
    public Mono<Long> countByGroup(AccountScope scope, String groupId) {
        return findByGroup(scope, groupId).count();
    }

    @Override
    public Flux<Membership> findByUser(String userId) {
        return Flux.defer(() -> Flux.fromIterable(memberships.values().stream().filter(m -> m.userId().equals(userId)).toList()));
    }

    @Override
    public Flux<Membership> findEverything() {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(memberships.values())));
    }

    @Override
    public Mono<Void> updateUserFields(String userId, String email, String name) {
        return Mono.fromRunnable(() -> {
            synchronized (memberships) {
                memberships.replaceAll((k, m) -> m.userId().equals(userId) ? m.withUserFields(email, name) : m);
            }
        });
    }
}

package com.iaf.console.identity.accesskeys.adapters.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import com.iaf.console.identity.accesskeys.domain.AccessKey;
import com.iaf.console.identity.accesskeys.ports.outbound.AccessKeyStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory access keys of the demo and the tests. */
public final class InMemoryAccessKeyStore implements AccessKeyStore {

    private final Map<String, AccessKey> keys = new ConcurrentSkipListMap<>();

    @Override
    public Mono<AccessKey> insert(AccessKey key) {
        return Mono.fromCallable(() -> {
            synchronized (keys) {
                if (keys.containsKey(key.keyId())) {
                    throw new ConflictException("the key id exists");
                }
                keys.put(key.keyId(), key);
                return key;
            }
        });
    }

    @Override
    public Mono<AccessKey> update(AccessKey key, long expectedVersion) {
        return Mono.fromCallable(() -> {
            synchronized (keys) {
                AccessKey current = keys.get(key.keyId());
                if (current == null || current.version() != expectedVersion) {
                    return null;
                }
                keys.put(key.keyId(), key);
                return key;
            }
        });
    }

    @Override
    public Mono<AccessKey> find(AccountScope scope, String keyId) {
        return Mono.justOrEmpty(keys.get(keyId)).filter(k -> k.accountId().equals(scope.accountId()));
    }

    @Override
    public Mono<Page<AccessKey>> list(AccountScope scope, String userId, int limit, String after) {
        return Mono.fromSupplier(() -> {
            String afterId = after == null ? null : CursorPaging.decode(after).id();
            List<AccessKey> fetched = keys.values().stream()
                    .filter(k -> k.accountId().equals(scope.accountId()))
                    .filter(k -> userId == null || k.userId().equals(userId))
                    .filter(k -> afterId == null || k.keyId().compareTo(afterId) > 0)
                    .limit(limit + 1L)
                    .toList();
            return Page.from(fetched, limit, k -> new Cursor(k.keyId(), k.keyId()));
        });
    }

    @Override
    public Flux<AccessKey> findByUser(AccountScope scope, String userId) {
        return findAll(scope).filter(k -> k.userId().equals(userId));
    }

    @Override
    public Flux<AccessKey> findAll(AccountScope scope) {
        return Flux.defer(() -> Flux.fromIterable(keys.values().stream().filter(k -> k.accountId().equals(scope.accountId())).toList()));
    }

    @Override
    public Mono<AccessKey> findByKeyId(String keyId) {
        return Mono.justOrEmpty(keys.get(keyId));
    }
}

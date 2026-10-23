package com.iaf.console.identity.common.store;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Predicate;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory twin of {@link MongoNamedStore}: the same rules over a sorted map. */
public final class InMemoryNamedStore<T> {

    private final Map<String, T> records = new ConcurrentSkipListMap<>();
    private final Keyed<T> keys;
    private final String what;

    public InMemoryNamedStore(Keyed<T> keys, String what) {
        this.keys = keys;
        this.what = what;
    }

    public Mono<T> insert(T record) {
        return Mono.fromCallable(() -> {
            synchronized (records) {
                if (records.containsKey(keys.id(record)) || nameTaken(record)) {
                    throw new ConflictException("a " + what + " named '" + keys.name(record) + "' exists");
                }
                records.put(keys.id(record), record);
                return record;
            }
        });
    }

    public Mono<T> update(T record, long expectedVersion) {
        return Mono.fromCallable(() -> {
            synchronized (records) {
                T current = records.get(keys.id(record));
                if (current == null || keys.version(current) != expectedVersion || !keys.accountId(current).equals(keys.accountId(record))) {
                    return null;
                }
                if (nameTaken(record)) {
                    throw new ConflictException("a " + what + " named '" + keys.name(record) + "' exists");
                }
                records.put(keys.id(record), record);
                return record;
            }
        });
    }

    private boolean nameTaken(T record) {
        return records.values().stream().anyMatch(r -> !keys.id(r).equals(keys.id(record)) && keys.accountId(r).equals(keys.accountId(record))
                && keys.name(r).equals(keys.name(record)));
    }

    public Mono<Boolean> delete(AccountScope scope, String id) {
        return Mono.fromSupplier(() -> {
            synchronized (records) {
                T current = records.get(id);
                if (current == null || !keys.accountId(current).equals(scope.accountId())) {
                    return false;
                }
                records.remove(id);
                return true;
            }
        });
    }

    public Mono<T> find(AccountScope scope, String id) {
        return Mono.fromSupplier(() -> records.get(id)).filter(r -> keys.accountId(r).equals(scope.accountId()));
    }

    public Mono<T> findByName(AccountScope scope, String name) {
        return Mono.justOrEmpty(records.values().stream().filter(r -> keys.accountId(r).equals(scope.accountId()) && keys.name(r).equals(name)).findFirst());
    }

    public Mono<Page<T>> list(AccountScope scope, int limit, String after, Predicate<T> extra) {
        return Mono.fromSupplier(() -> {
            String afterId = after == null ? null : CursorPaging.decode(after).id();
            List<T> fetched = records.values().stream()
                    .filter(r -> keys.accountId(r).equals(scope.accountId()))
                    .filter(r -> extra == null || extra.test(r))
                    .filter(r -> afterId == null || keys.id(r).compareTo(afterId) > 0)
                    .limit(limit + 1L)
                    .toList();
            return Page.from(fetched, limit, r -> new Cursor(keys.id(r), keys.id(r)));
        });
    }

    public Flux<T> findAll(AccountScope scope) {
        return Flux.defer(() -> Flux.fromIterable(records.values().stream().filter(r -> keys.accountId(r).equals(scope.accountId())).toList()));
    }

    public Flux<T> findWhere(Predicate<T> filter) {
        return Flux.defer(() -> Flux.fromIterable(records.values().stream().filter(filter).toList()));
    }

    public Flux<T> findEverything() {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(records.values())));
    }

    public int size() {
        return records.size();
    }
}

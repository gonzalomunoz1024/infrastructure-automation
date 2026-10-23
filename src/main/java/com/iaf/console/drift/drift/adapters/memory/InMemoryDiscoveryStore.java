package com.iaf.console.drift.drift.adapters.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.drift.drift.domain.DiscoveryRun;
import com.iaf.console.drift.drift.domain.UnmanagedObject;
import com.iaf.console.drift.drift.ports.outbound.DiscoveryStorePort;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Discovery runs and unmanaged objects in memory, for the demo and the tests. */
public final class InMemoryDiscoveryStore implements DiscoveryStorePort {

    private final Map<String, DiscoveryRun> runs = new ConcurrentHashMap<>();
    private final Map<String, UnmanagedObject> unmanaged = new ConcurrentHashMap<>();

    /** The key of an unmanaged object: one per account, type, region and provider id. */
    public static String key(String accountId, String type, String region, String providerId) {
        return accountId + "|" + type + "|" + region + "|" + providerId;
    }

    @Override
    public Mono<DiscoveryRun> findRun(AccountScope scope, String runId) {
        return Mono.justOrEmpty(runs.get(runId)).filter(r -> scope.accountId().equals(r.accountId()));
    }

    @Override
    public Mono<DiscoveryRun> findRunningRun(AccountScope scope, String type, String region) {
        return Flux.fromIterable(runs.values())
                .filter(r -> r.running() && scope.accountId().equals(r.accountId()) && type.equals(r.type()) && region.equals(r.region()))
                .next();
    }

    @Override
    public Mono<DiscoveryRun> saveRun(DiscoveryRun run) {
        return Mono.fromSupplier(() -> {
            runs.put(run.runId(), run);
            return run;
        });
    }

    @Override
    public Mono<Page<UnmanagedObject>> listUnmanaged(AccountScope scope, String type, String region, String owner, int limit, String after) {
        return Mono.fromSupplier(() -> {
            String from = after == null || after.isBlank() ? null : CursorPaging.decode(after).id();
            List<Map.Entry<String, UnmanagedObject>> fetched = unmanaged.entrySet().stream()
                    .filter(e -> scope.accountId().equals(e.getValue().accountId()))
                    .filter(e -> type == null || type.equals(e.getValue().type()))
                    .filter(e -> region == null || region.equals(e.getValue().region()))
                    .filter(e -> owned(e.getValue(), scope.accountId(), owner))
                    .filter(e -> from == null || e.getKey().compareTo(from) > 0)
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .limit(limit + 1L)
                    .toList();
            Page<Map.Entry<String, UnmanagedObject>> page = Page.from(fetched, limit, e -> new Cursor(e.getKey(), e.getKey()));
            return page.map(Map.Entry::getValue);
        });
    }

    @Override
    public Mono<UnmanagedObject> findUnmanaged(AccountScope scope, String type, String region, String providerId) {
        return Mono.justOrEmpty(unmanaged.get(key(scope.accountId(), type, region, providerId)));
    }

    @Override
    public Mono<UnmanagedObject> saveUnmanaged(UnmanagedObject object) {
        return Mono.fromSupplier(() -> {
            unmanaged.put(key(object.accountId(), object.type(), object.region(), object.providerId()), object);
            return object;
        });
    }

    @Override
    public Mono<Void> deleteUnmanaged(AccountScope scope, String type, String region, String providerId) {
        return Mono.fromRunnable(() -> unmanaged.remove(key(scope.accountId(), type, region, providerId)));
    }

    @Override
    public Flux<UnmanagedObject> findCopies(String type, String region, String providerId) {
        return Flux.fromIterable(List.copyOf(unmanaged.values()))
                .filter(o -> type.equals(o.type()) && region.equals(o.region()) && providerId.equals(o.providerId()));
    }

    /** The owner filter of a list: {@code mine} is guessed to be or assigned to the account, {@code unknown} has no guess. */
    static boolean owned(UnmanagedObject o, String accountId, String owner) {
        if (MINE.equals(owner)) {
            return o.ownerGuess() != null && accountId.equals(o.ownerGuess().accountId())
                    || o.assignment() != null && accountId.equals(o.assignment().accountId());
        }
        if (UNKNOWN.equals(owner)) {
            return o.ownerGuess() == null;
        }
        return true;
    }
}

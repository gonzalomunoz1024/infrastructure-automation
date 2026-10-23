package com.iaf.console.drift.monitoring.adapters.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.drift.monitoring.domain.Alarm;
import com.iaf.console.drift.monitoring.domain.AlarmQuery;
import com.iaf.console.drift.monitoring.ports.outbound.AlarmStorePort;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The alarms in memory, for the demo and the tests, with the same conditional writes as the MongoDB store. */
public final class InMemoryAlarmStore implements AlarmStorePort {

    static final Comparator<Alarm> LIST_ORDER = Comparator.comparingInt((Alarm a) -> a.state().rank()).thenComparing(Alarm::alarmId);

    private final Map<String, Alarm> byKey = new ConcurrentHashMap<>();

    private static String key(String accountId, String alarmId) {
        return accountId + ":" + alarmId;
    }

    @Override
    public Mono<Alarm> find(AccountScope scope, String alarmId) {
        return Mono.justOrEmpty(byKey.get(key(scope.accountId(), alarmId)));
    }

    @Override
    public Mono<Page<Alarm>> list(AccountScope scope, AlarmQuery query) {
        return Mono.fromSupplier(() -> {
            Cursor after = query.after();
            List<Alarm> fetched = byKey.values().stream()
                    .filter(a -> a.accountId().equals(scope.accountId()))
                    .filter(a -> query.wfrn() == null || query.wfrn().equals(a.wfrn()))
                    .filter(a -> query.state() == null || query.state() == a.state())
                    .filter(a -> after == null || isAfter(a, after))
                    .sorted(LIST_ORDER)
                    .limit(query.limit() + 1L)
                    .toList();
            return Page.from(fetched, query.limit(), a -> new Cursor((long) a.state().rank(), a.alarmId()));
        });
    }

    private static boolean isAfter(Alarm a, Cursor after) {
        long rank = after.sortLong();
        return a.state().rank() > rank || a.state().rank() == rank && a.alarmId().compareTo(after.id()) > 0;
    }

    @Override
    public Flux<Alarm> findAll() {
        return Flux.fromStream(() -> byKey.values().stream()
                .sorted(Comparator.comparing(Alarm::wfrn).thenComparing(Alarm::accountId).thenComparing(Alarm::alarmId)));
    }

    @Override
    public Mono<Alarm> save(Alarm alarm) {
        return Mono.fromCallable(() -> {
            Alarm[] saved = new Alarm[1];
            byKey.compute(key(alarm.accountId(), alarm.alarmId()), (k, current) -> {
                long stored = current == null ? 0 : current.rev();
                if (stored != alarm.rev()) {
                    return current;
                }
                saved[0] = alarm.withRev(stored + 1);
                return saved[0];
            });
            if (saved[0] == null) {
                throw new Conflict(alarm.accountId(), alarm.alarmId());
            }
            return saved[0];
        });
    }

    @Override
    public Mono<Boolean> delete(AccountScope scope, String alarmId) {
        return Mono.fromSupplier(() -> byKey.remove(key(scope.accountId(), alarmId)) != null);
    }
}

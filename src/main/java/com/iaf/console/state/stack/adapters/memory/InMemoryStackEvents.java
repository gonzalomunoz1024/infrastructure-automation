package com.iaf.console.state.stack.adapters.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.stack.domain.StackEvent;
import com.iaf.console.state.stack.ports.outbound.StackEventStorePort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Stacks' logs in memory: each event once, by its key. */
public final class InMemoryStackEvents implements StackEventStorePort {

    private final Map<String, List<StackEvent>> byStack = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> append(List<StackEvent> events) {
        return Mono.fromRunnable(() -> events.forEach(e -> {
            List<StackEvent> log = byStack.computeIfAbsent(e.wfrn(), k -> new ArrayList<>());
            synchronized (log) {
                if (log.stream().noneMatch(x -> x.key().equals(e.key()))) {
                    log.add(e);
                }
            }
        }));
    }

    @Override
    public Flux<StackEvent> list(AccountScope scope, String wfrn, long afterVersion, int limit) {
        List<StackEvent> log = byStack.getOrDefault(wfrn, List.of());
        List<StackEvent> copy;
        synchronized (log) {
            copy = new ArrayList<>(log);
        }
        return Flux.fromStream(copy.stream()
                .filter(e -> scope.covers(e.accountId(), e.region()))
                .filter(e -> e.version() > afterVersion)
                .sorted(Comparator.comparingLong(StackEvent::version))
                .limit(limit));
    }
}

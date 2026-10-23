package com.iaf.console.bff.notifications.adapters.outbound;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.notifications.domain.OutboxEntry;
import com.iaf.console.bff.notifications.ports.outbound.NotificationOutbox;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The outbox of one instance, in memory; what was sent or failed is kept for the record, the oldest let go past 10,000. */
@Component
@Profile("!mongo")
public class InMemoryNotificationOutbox implements NotificationOutbox {

    static final int KEPT = 10_000;

    private final Map<String, OutboxEntry> byKey = new LinkedHashMap<>();

    @Override
    public Mono<Boolean> enqueue(OutboxEntry entry) {
        return Mono.fromCallable(() -> {
            synchronized (byKey) {
                if (byKey.containsKey(entry.key())) {
                    return false;
                }
                byKey.put(entry.key(), entry);
                if (byKey.size() > KEPT) {
                    byKey.entrySet().removeIf(e -> byKey.size() > KEPT && !OutboxEntry.PENDING.equals(e.getValue().status()));
                }
                return true;
            }
        });
    }

    @Override
    public Flux<OutboxEntry> claimDue(Instant now, Duration lease, int max) {
        return Flux.defer(() -> {
            List<OutboxEntry> claimed = new ArrayList<>();
            synchronized (byKey) {
                byKey.values().stream().filter(e -> e.dueBy(now)).sorted(Comparator.comparing(OutboxEntry::nextAttemptAt)).limit(max).toList()
                        .forEach(e -> {
                            OutboxEntry held = e.held(now, lease);
                            byKey.put(e.key(), held);
                            claimed.add(held);
                        });
            }
            return Flux.fromIterable(claimed);
        });
    }

    @Override
    public Mono<Void> save(OutboxEntry entry) {
        return Mono.fromRunnable(() -> {
            synchronized (byKey) {
                byKey.put(entry.key(), entry);
            }
        });
    }

    /** What the outbox holds now, for tests. */
    public List<OutboxEntry> entries() {
        synchronized (byKey) {
            return List.copyOf(byKey.values());
        }
    }
}

package com.iaf.console.bff.assistant.adapters.outbound;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.assistant.domain.TurnAllowance;
import com.iaf.console.bff.assistant.ports.outbound.TurnLimiter;

import reactor.core.publisher.Mono;

/** A person's turns in this instance, as the times of their turns in the last window. */
@Component
@Profile("!mongo")
public class InMemoryTurnLimiter implements TurnLimiter {

    private final Map<String, Deque<Instant>> byUser = new ConcurrentHashMap<>();

    @Override
    public Mono<TurnAllowance> take(String userId, Instant now, int limit, Duration window) {
        return Mono.fromSupplier(() -> {
            Deque<Instant> taken = byUser.computeIfAbsent(userId, id -> new ArrayDeque<>());
            synchronized (taken) {
                Instant start = now.minus(window);
                while (!taken.isEmpty() && !taken.peekFirst().isAfter(start)) {
                    taken.pollFirst();
                }
                if (taken.size() >= limit) {
                    return TurnAllowance.refused(Duration.between(now, taken.peekFirst().plus(window)));
                }
                taken.addLast(now);
                return TurnAllowance.granted();
            }
        });
    }
}

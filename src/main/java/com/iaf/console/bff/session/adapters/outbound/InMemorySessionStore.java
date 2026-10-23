package com.iaf.console.bff.session.adapters.outbound;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.ports.outbound.SessionStore;

import reactor.core.publisher.Mono;

/** Sessions of one instance, in memory; expired ones are swept now and then. */
@Component
@Profile("!mongo")
public class InMemorySessionStore implements SessionStore {

    private static final int SWEEP_EVERY = 500;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong writes = new AtomicLong();
    private final Clock clock;

    public InMemorySessionStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<Session> find(String id) {
        return Mono.justOrEmpty(sessions.get(id));
    }

    @Override
    public Mono<Void> save(Session session) {
        return Mono.fromRunnable(() -> {
            sessions.put(session.id(), session);
            if (writes.incrementAndGet() % SWEEP_EVERY == 0) {
                sessions.values().removeIf(s -> s.expired(clock.instant()));
            }
        });
    }

    @Override
    public Mono<Void> delete(String id) {
        return Mono.fromRunnable(() -> sessions.remove(id));
    }

    public int size() {
        return sessions.size();
    }
}

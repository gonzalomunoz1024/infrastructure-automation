package com.iaf.console.bff.session.adapters.outbound;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.SessionKeys;
import com.iaf.console.bff.session.ports.outbound.SessionStore;

import reactor.core.publisher.Mono;

/**
 * Sessions of one instance, in memory, keyed on the SHA-256 of their ids and held with that hash
 * in place of the id; expired ones are swept now and then.
 */
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
        return Mono.fromSupplier(() -> sessions.get(SessionKeys.of(id))).map(stored -> stored.withId(id));
    }

    @Override
    public Mono<Void> create(Session session) {
        return Mono.fromRunnable(() -> {
            String key = SessionKeys.of(session.id());
            sessions.put(key, session.withId(key));
            sweepNowAndThen();
        });
    }

    @Override
    public Mono<Boolean> update(Session session) {
        return Mono.fromSupplier(() -> {
            String key = SessionKeys.of(session.id());
            // computeIfPresent: a session deleted meanwhile stays deleted
            return sessions.computeIfPresent(key, (k, old) -> session.withId(key)) != null;
        });
    }

    @Override
    public Mono<Void> delete(String id) {
        return Mono.fromRunnable(() -> sessions.remove(SessionKeys.of(id)));
    }

    public int size() {
        return sessions.size();
    }

    /** The keys sessions are held under, for the tests. */
    Set<String> keys() {
        return Set.copyOf(sessions.keySet());
    }

    private void sweepNowAndThen() {
        if (writes.incrementAndGet() % SWEEP_EVERY == 0) {
            sessions.values().removeIf(s -> s.expired(clock.instant()));
        }
    }
}

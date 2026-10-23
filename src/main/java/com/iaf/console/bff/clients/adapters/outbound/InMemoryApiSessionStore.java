package com.iaf.console.bff.clients.adapters.outbound;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.clients.domain.ApiSession;
import com.iaf.console.bff.clients.ports.outbound.ApiSessionStore;

import reactor.core.publisher.Mono;

/** API sessions of one instance, in memory; the ones past their end are swept now and then. */
@Component
@Profile("!mongo")
public class InMemoryApiSessionStore implements ApiSessionStore {

    /** Newest first: by creation, then by id. */
    static final Comparator<ApiSession> NEWEST_FIRST = Comparator.comparing(ApiSession::createdAt).thenComparing(ApiSession::id).reversed();
    private static final int SWEEP_EVERY = 200;

    private final Map<String, ApiSession> sessions = new HashMap<>();
    private final Map<String, String> idByRefreshHash = new HashMap<>();
    private final Clock clock;
    private long writes;

    public InMemoryApiSessionStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<Void> create(ApiSession session) {
        return Mono.fromRunnable(() -> {
            synchronized (this) {
                if (sessions.containsKey(session.id()) || idByRefreshHash.containsKey(session.refreshHash())) {
                    throw new IllegalStateException("the API session " + session.id() + " exists already");
                }
                put(session);
            }
        });
    }

    @Override
    public Mono<ApiSession> find(String id) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                return sessions.get(id);
            }
        });
    }

    @Override
    public Mono<ApiSession> findByRefreshToken(String refreshHash) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                String id = idByRefreshHash.get(refreshHash);
                return id == null ? null : sessions.get(id);
            }
        });
    }

    @Override
    public Mono<ApiSession> findByRotatedToken(String refreshHash) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                return sessions.values().stream().filter(s -> s.rotatedHashes().contains(refreshHash)).findFirst().orElse(null);
            }
        });
    }

    @Override
    public Mono<List<ApiSession>> listOpen(String userId, Instant now, int limit, Instant afterCreatedAt, String afterId) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                return sessions.values().stream()
                        .filter(s -> s.user().userId().equals(userId) && s.openAt(now))
                        .filter(s -> afterId == null || s.createdAt().isBefore(afterCreatedAt)
                                || s.createdAt().equals(afterCreatedAt) && s.id().compareTo(afterId) < 0)
                        .sorted(NEWEST_FIRST)
                        .limit(limit)
                        .toList();
            }
        });
    }

    @Override
    public Mono<ApiSession> update(ApiSession session, long expectedVersion) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                ApiSession current = sessions.get(session.id());
                if (current == null || current.version() != expectedVersion) {
                    return null;
                }
                idByRefreshHash.remove(current.refreshHash());
                put(session);
                return session;
            }
        });
    }

    private void put(ApiSession session) {
        sessions.put(session.id(), session);
        idByRefreshHash.put(session.refreshHash(), session.id());
        if (++writes % SWEEP_EVERY == 0) {
            Instant now = clock.instant();
            sessions.values().removeIf(s -> {
                if (now.isBefore(s.purgeAt())) {
                    return false;
                }
                idByRefreshHash.remove(s.refreshHash());
                return true;
            });
        }
    }
}

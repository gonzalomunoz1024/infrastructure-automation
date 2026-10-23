package com.iaf.console.bff.clients.ports.outbound;

import java.time.Instant;
import java.util.List;

import com.iaf.console.bff.clients.domain.ApiSession;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/**
 * Where API sessions live: memory for one instance, Mongo ({@code api_sessions}) for many. A
 * session is found by its id, by its current refresh token's hash or by one it rotated away, and
 * forgotten once it ended ({@link ApiSession#purgeAt()}).
 */
@GlobalRead("an API session belongs to a person, not to an account, and is found by its own id or refresh token")
public interface ApiSessionStore {

    Mono<Void> create(ApiSession session);

    /** The session of an id, open or not; empty when none is kept. */
    Mono<ApiSession> find(String id);

    /** The session whose current refresh token has this SHA-256; empty when none. */
    Mono<ApiSession> findByRefreshToken(String refreshHash);

    /** The session that rotated away a refresh token with this SHA-256; empty when none. */
    Mono<ApiSession> findByRotatedToken(String refreshHash);

    /**
     * A person's sessions open at {@code now}, newest first (by {@code createdAt}, then by id): at
     * most {@code limit}, and when {@code afterId} is given only those that come after the session
     * created at {@code afterCreatedAt} with that id.
     */
    Mono<List<ApiSession>> listOpen(String userId, Instant now, int limit, Instant afterCreatedAt, String afterId);

    /** Replaces a session that is still at {@code expectedVersion}; empty when it changed meanwhile or is gone. */
    Mono<ApiSession> update(ApiSession session, long expectedVersion);
}

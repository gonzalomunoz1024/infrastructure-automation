package com.iaf.console.bff.session.ports.outbound;

import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/**
 * Where sessions live: memory for one instance, Mongo for many. A store keys a session on the
 * SHA-256 of its id ({@link com.iaf.console.bff.session.domain.SessionKeys}) and never keeps the
 * id itself. Only {@link #create} inserts: {@link #update} changes a session that is still there
 * and never brings back one that was deleted (a logout racing a request in flight).
 */
@GlobalRead("a session is found by its own id before any account scope exists")
public interface SessionStore {

    Mono<Session> find(String id);

    /** Stores a new session. */
    Mono<Void> create(Session session);

    /** Replaces a stored session; false, with nothing written, when it is no longer there. */
    Mono<Boolean> update(Session session);

    Mono<Void> delete(String id);
}

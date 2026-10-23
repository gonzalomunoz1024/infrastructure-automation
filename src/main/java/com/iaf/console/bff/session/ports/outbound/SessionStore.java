package com.iaf.console.bff.session.ports.outbound;

import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** Where sessions live: memory for one instance, Mongo for many. */
@GlobalRead("a session is found by its own id before any account scope exists")
public interface SessionStore {

    Mono<Session> find(String id);

    Mono<Void> save(Session session);

    Mono<Void> delete(String id);
}

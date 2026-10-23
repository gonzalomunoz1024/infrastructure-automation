package com.iaf.console.identity.principals.ports.outbound;

import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where users live: one per person for the whole console, so every read is global. */
@GlobalRead
public interface UserStore {

    Mono<User> insert(User user);

    /** Replaces the user; the version is not checked (sign-in touches do not move it). */
    Mono<User> save(User user);

    Mono<User> findById(String userId);

    Mono<User> findByEmail(String email);

    Mono<User> findByIssuerAndSubject(String issuer, String subject);

    Flux<User> findByIds(java.util.List<String> userIds);

    Flux<User> findAll();
}

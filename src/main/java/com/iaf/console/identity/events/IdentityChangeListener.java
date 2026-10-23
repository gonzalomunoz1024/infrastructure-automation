package com.iaf.console.identity.events;

import reactor.core.publisher.Mono;

/** Something that runs in the same chain as an identity event's append (the authorization version, a cache). */
@FunctionalInterface
public interface IdentityChangeListener {

    Mono<Void> changed(IdentityChange change);
}

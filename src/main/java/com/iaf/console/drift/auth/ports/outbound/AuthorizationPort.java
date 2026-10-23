package com.iaf.console.drift.auth.ports.outbound;

import com.iaf.console.drift.auth.domain.ConsolePrincipal;

import reactor.core.publisher.Mono;

/**
 * Whether a person may do an action ({@code <service>:<verb>}) on a resource or a wfrn pattern:
 * the identity service's decision, or the managed roles where no identity service runs (the demos).
 */
public interface AuthorizationPort {

    Mono<Boolean> allowed(ConsolePrincipal person, String action, String resource);
}

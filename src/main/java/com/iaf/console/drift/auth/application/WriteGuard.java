package com.iaf.console.drift.auth.application;

import com.iaf.console.drift.auth.domain.ConsolePrincipal;
import com.iaf.console.drift.auth.ports.outbound.AuthorizationPort;
import com.iaf.console.kit.errors.ForbiddenException;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * The service's own check on every write a person asks for, behind the BFF's: a revert, an accept
 * or an ignore, an alarm, a recommendation applied or dismissed, an unmanaged object imported or
 * handed on. The BFF checks the same actions before it calls, so a person never meets this one
 * unless a token reaches the service another way; a viewer's or an auditor's token is then refused
 * here. The console's own services pass: only the allowed ones get through authentication.
 */
public final class WriteGuard {

    private final AuthorizationPort authorization;
    private final String partition;

    public WriteGuard(AuthorizationPort authorization, String partition) {
        this.authorization = authorization;
        this.partition = partition;
    }

    /** Completes when the caller may do {@code <service>:<verb>} on the resource, the service read from its wfrn; a 403 otherwise. */
    public Mono<Void> require(ConsolePrincipal caller, String verb, String wfrn) {
        if (caller.internal()) {
            return Mono.empty();
        }
        Wfrn name = Wfrn.parse(wfrn);
        return decide(caller, name.service() + ":" + verb, wfrn);
    }

    /** As {@link #require}, on every resource of a type in an account and region, for a command that makes one (an import). */
    public Mono<Void> requireOnType(ConsolePrincipal caller, String service, String verb, String accountId, String region, String type) {
        if (caller.internal()) {
            return Mono.empty();
        }
        return decide(caller, service + ":" + verb, "wfrn:" + partition + ":" + service + ":" + region + ":" + accountId + ":" + type + "/*");
    }

    /**
     * Who a command is recorded for: always the person who calls; a body's {@code actor} only from one
     * of the console's own services, which may act for someone it names.
     */
    public static String actor(ConsolePrincipal caller, String asked) {
        if (caller.internal() && asked != null && !asked.isBlank()) {
            return asked.strip();
        }
        return caller.subject();
    }

    private Mono<Void> decide(ConsolePrincipal caller, String action, String resource) {
        return authorization.allowed(caller, action, resource)
                .defaultIfEmpty(false)
                .flatMap(allowed -> allowed ? Mono.<Void>empty() : Mono.error(new ForbiddenException(action + " is not allowed on " + resource)));
    }
}

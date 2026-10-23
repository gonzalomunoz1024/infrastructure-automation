package com.iaf.console.drift.drift.adapters.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.application.FindingQueries;
import com.iaf.console.drift.drift.application.IdempotentCommands;
import com.iaf.console.drift.auth.domain.ConsolePrincipal;
import com.iaf.console.drift.drift.ports.outbound.OnBehalfOfPort;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** Who calls, the scope a call acts in, and how a command's outcome is answered. */
final class Principals {

    /** Set on the answer of a command replayed under its Idempotency-Key. */
    static final String REPLAY_HEADER = "Idempotent-Replay";

    private Principals() {
    }

    static ConsolePrincipal principal(ServerWebExchange exchange) {
        ConsolePrincipal principal = exchange.getAttribute(ConsolePrincipal.ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("no principal on the exchange: the authentication filter did not run");
        }
        return principal;
    }

    /** The account a call reads or acts in: the caller's own, or the named one for the console's own services. */
    static AccountScope scope(ServerWebExchange exchange, String accountId, String region) {
        if (accountId != null && !AccountScope.isAccountId(accountId)) {
            throw new IllegalArgumentException("accountId is 12 digits");
        }
        return principal(exchange).scope(accountId, region);
    }

    /**
     * The scope of a call on one finding: the caller's account, or, for the console's own services
     * (which name no account), the account the finding belongs to.
     */
    static Mono<AccountScope> findingScope(ServerWebExchange exchange, String findingId, String accountId, FindingQueries queries) {
        ConsolePrincipal principal = principal(exchange);
        if (principal.internal() && accountId == null) {
            return queries.getAny(findingId).map(f -> AccountScope.of(f.accountId(), f.region(), principal.subject()));
        }
        return Mono.fromCallable(() -> scope(exchange, accountId, null));
    }

    /** The person a revert runs for: the caller when it is a person of the account; none for a service. */
    static OnBehalfOfPort.Person person(ConsolePrincipal principal) {
        if (principal.internal()) {
            return null;
        }
        return new OnBehalfOfPort.Person(principal.subject(), null, null, principal.groups());
    }

    /** Only the console's own services and operators; a person hears 404, as if the route were not there. */
    static ConsolePrincipal internal(ServerWebExchange exchange) {
        ConsolePrincipal principal = principal(exchange);
        if (!principal.internal()) {
            throw new NotFoundException("not found");
        }
        return principal;
    }

    static ResponseEntity<ObjectNode> respond(IdempotentCommands.Outcome outcome) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(outcome.status());
        if (outcome.replay()) {
            builder.header(REPLAY_HEADER, "true");
        }
        return builder.body(outcome.body());
    }
}

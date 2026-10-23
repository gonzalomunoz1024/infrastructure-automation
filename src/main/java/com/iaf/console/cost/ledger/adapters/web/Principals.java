package com.iaf.console.cost.ledger.adapters.web;

import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.cost.auth.domain.ConsolePrincipal;
import com.iaf.console.cost.auth.domain.RouteGroup;
import com.iaf.console.kit.errors.NotFoundException;

/** The principal the authentication filter put on the exchange. */
final class Principals {

    private Principals() {
    }

    static ConsolePrincipal of(ServerWebExchange exchange) {
        ConsolePrincipal principal = exchange.getAttribute(ConsolePrincipal.ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("no principal on the exchange: the authentication filter did not run");
        }
        return principal;
    }

    /**
     * A console service allowed the ledger's operator calls: a person's token finds nothing here
     * (404), as in the other services, and a service not allowed them gets 403.
     */
    static ConsolePrincipal ledger(ServerWebExchange exchange) {
        ConsolePrincipal principal = of(exchange);
        if (!principal.internal()) {
            throw new NotFoundException("not found");
        }
        principal.require(RouteGroup.LEDGER);
        return principal;
    }
}

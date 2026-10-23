package com.iaf.console.cost.budgets.adapters.web;

import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.cost.auth.domain.ConsolePrincipal;
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

    /** The internal principal, or a 404: a user's token finds nothing here, as in the other services. */
    static ConsolePrincipal internal(ServerWebExchange exchange) {
        ConsolePrincipal principal = of(exchange);
        if (!principal.internal()) {
            throw new NotFoundException("not found");
        }
        return principal;
    }
}

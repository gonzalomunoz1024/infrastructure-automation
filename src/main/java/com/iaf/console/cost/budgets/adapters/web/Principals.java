package com.iaf.console.cost.budgets.adapters.web;

import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.cost.auth.domain.ConsolePrincipal;

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
}

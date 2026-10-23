package com.iaf.console.cost.pricing.adapters.web;

import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.cost.auth.domain.ConsolePrincipal;
import com.iaf.console.cost.auth.domain.RouteGroup;

/** The principal the authentication filter put on the exchange. */
final class Principals {

    private Principals() {
    }

    /** Any person, or a console service allowed the price lists and estimates (403 for another). */
    static ConsolePrincipal pricing(ServerWebExchange exchange) {
        ConsolePrincipal principal = exchange.getAttribute(ConsolePrincipal.ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("no principal on the exchange: the authentication filter did not run");
        }
        principal.require(RouteGroup.PRICING);
        return principal;
    }
}

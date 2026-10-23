package com.iaf.console.drift.monitoring.adapters.web;

import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.drift.auth.domain.ConsolePrincipal;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

/**
 * Who calls the monitoring API and the account a call acts in: a person's own account, or for the
 * console's own services the account they name, or the one the wfrn names. Another account's
 * resource is then simply not found.
 */
final class Callers {

    /** The region a scope carries when the token names none; the stores scope by account alone. */
    static final String DEFAULT_REGION = "global";

    private Callers() {
    }

    static ConsolePrincipal principal(ServerWebExchange exchange) {
        ConsolePrincipal principal = exchange.getAttribute(ConsolePrincipal.ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("no principal on the exchange: the authentication filter did not run");
        }
        return principal;
    }

    /** The caller's own account, or the named one for an internal principal (which must name one). */
    static AccountScope scope(ServerWebExchange exchange, String accountId) {
        if (accountId != null && !AccountScope.isAccountId(accountId)) {
            throw new IllegalArgumentException("accountId is 12 digits");
        }
        ConsolePrincipal principal = principal(exchange);
        return principal.scope(accountId, principal.region() == null ? DEFAULT_REGION : null);
    }

    /**
     * The scope of a call about one resource: a person's own account (a wfrn of another account is
     * then not found), or for an internal principal the account it names, else the wfrn's.
     */
    static AccountScope scopeOf(ServerWebExchange exchange, String accountId, String wfrn) {
        ConsolePrincipal principal = principal(exchange);
        if (principal.internal() && accountId == null && wfrn != null) {
            Wfrn name = Wfrn.parse(wfrn.trim());
            String region = principal.region() != null ? principal.region() : name.region().isEmpty() ? DEFAULT_REGION : name.region();
            if (!AccountScope.isAccountId(name.account())) {
                throw NotFoundException.of("resource", wfrn);
            }
            return AccountScope.of(name.account(), region, principal.subject());
        }
        return scope(exchange, accountId);
    }

    /** Only the console's own services and operators; a person hears 404, as if the route were not there. */
    static ConsolePrincipal internal(ServerWebExchange exchange) {
        ConsolePrincipal principal = principal(exchange);
        if (!principal.internal()) {
            throw new NotFoundException("not found");
        }
        return principal;
    }
}

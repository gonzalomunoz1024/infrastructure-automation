package com.iaf.console.cost.auth.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The console services this one answers when they call as themselves (hub decision 35): a token
 * without {@code acct} whose {@code svc} is its {@code sub}, from a service listed here, for the
 * route groups it is listed under. A token without an account from any other service is refused
 * (401); a listed service outside its groups gets 403. The defaults: {@code console-bff} for every
 * route, {@code console-drift-service} for the price lists and estimates only.
 */
public record ServicePrincipals(Set<String> pricing, Set<String> accounts, Set<String> ledger) {

    public static final String BFF = "console-bff";
    public static final String DRIFT = "console-drift-service";

    public ServicePrincipals {
        pricing = ids("pricing", pricing);
        accounts = ids("accounts", accounts);
        ledger = ids("ledger", ledger);
    }

    /** The console's own callers as hub decision 35 and the cost contract list them. */
    public static ServicePrincipals defaults() {
        return new ServicePrincipals(Set.of(BFF, DRIFT), Set.of(BFF), Set.of(BFF));
    }

    /** The route groups a service may call as itself; empty when it may not call this service at all. */
    public Set<RouteGroup> routesOf(String service) {
        Set<RouteGroup> routes = EnumSet.noneOf(RouteGroup.class);
        if (service == null) {
            return routes;
        }
        if (pricing.contains(service)) {
            routes.add(RouteGroup.PRICING);
        }
        if (accounts.contains(service)) {
            routes.add(RouteGroup.ACCOUNTS);
        }
        if (ledger.contains(service)) {
            routes.add(RouteGroup.LEDGER);
        }
        return routes;
    }

    /** Every service that may call as itself, for a log line or an error. */
    public Set<String> all() {
        Set<String> all = new TreeSet<>(pricing);
        all.addAll(accounts);
        all.addAll(ledger);
        return all;
    }

    private static Set<String> ids(String group, Collection<String> ids) {
        if (ids == null) {
            return Set.of();
        }
        for (String id : ids) {
            if (id == null || id.isBlank() || !id.equals(id.strip())) {
                throw new IllegalArgumentException("the services allowed for " + group + " name each service by its id, was '" + id + "'");
            }
        }
        return Set.copyOf(ids);
    }
}

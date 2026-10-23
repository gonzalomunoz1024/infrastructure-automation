package com.iaf.console.cost.auth.domain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.iaf.console.kit.errors.ForbiddenException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

/**
 * Who calls: the claims of the console's internal JWT. A person carries {@code acct}, acts in that
 * account only and comes through the BFF; {@code roles} are the managed role names they hold
 * there. A console service calling as itself carries no account and is {@link #internal()}: it is
 * one of the {@link ServicePrincipals} (hub decision 35), reaches the {@link RouteGroup}s it is
 * allowed, and names the account of a call when it may reach {@link RouteGroup#ACCOUNTS}.
 */
public record ConsolePrincipal(String subject, String accountId, String region, List<String> groups, List<String> roles, String service,
        Set<RouteGroup> routes) {

    /** The region of a read that names none, on a service token. */
    public static final String DEFAULT_REGION = "global";


    /** The exchange attribute the authentication filter stores the principal under. */
    public static final String ATTRIBUTE = "com.iaf.console.cost.principal";

    /** The most accounts one read may name (the organization view). */
    public static final int MAX_ACCOUNTS = 500;

    /** The managed role whose holders change budgets: {@code budgets:create}, {@code :update} and {@code :delete} are its alone (decision 25). */
    public static final String ACCOUNT_ADMIN = "account-admin";

    public ConsolePrincipal {
        Objects.requireNonNull(subject, "subject");
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
        routes = routes == null ? Set.of() : Set.copyOf(routes);
    }

    /** A person in their account, as the BFF relays them ({@code service} names the BFF). */
    public static ConsolePrincipal person(String subject, String accountId, String region, List<String> groups, List<String> roles, String service) {
        return new ConsolePrincipal(subject, Objects.requireNonNull(accountId, "accountId"), region, groups, roles, service, Set.of());
    }

    /** A console service calling as itself, for the route groups it is allowed. */
    public static ConsolePrincipal service(String service, String region, Set<RouteGroup> routes) {
        return new ConsolePrincipal(service, null, region, List.of(), List.of(), service, routes);
    }

    /** A principal that acts for no account: one of the console's own services. */
    public boolean internal() {
        return accountId == null;
    }

    /** The same principal in the service's own region when its token named none, as a service token does. */
    public ConsolePrincipal orRegion(String fallback) {
        return region != null || fallback == null ? this : new ConsolePrincipal(subject, accountId, fallback, groups, roles, service, routes);
    }

    /**
     * Refuses (403) a console service that is not allowed this route group. A person is not limited
     * here: their account bounds them ({@link #scope}), and the ledger's calls answer them 404.
     */
    public void require(RouteGroup group) {
        if (internal() && !routes.contains(group)) {
            throw new ForbiddenException(subject + " may not call the " + group.name().toLowerCase(Locale.ROOT) + " routes of the cost service");
        }
    }

    /**
     * Refuses (403) a change of a budget by a person without the {@code account-admin} role in the
     * account, or by a console service that may not reach accounts: defence in depth behind the
     * BFF's {@code budgets:*} decision, as the managed roles give budget changes to admins alone.
     */
    public void requireBudgetChanges() {
        if (internal()) {
            require(RouteGroup.ACCOUNTS);
        } else if (!roles.contains(ACCOUNT_ADMIN)) {
            throw new ForbiddenException("changing a budget takes the " + ACCOUNT_ADMIN + " role in the account");
        }
    }

    /**
     * The scope of a read: the principal's own account, or the named account for a console service
     * that may reach accounts (any other service is a 403). Another account's name answers a person
     * 404, never 403, so ids cannot be probed.
     */
    public AccountScope scope(String requestedAccountId, String requestedRegion) {
        require(RouteGroup.ACCOUNTS);
        String account = requestedAccountId != null && !requestedAccountId.isBlank() ? requestedAccountId : accountId;
        if (account == null) {
            throw new IllegalArgumentException("accountId is required for a call that acts for no account");
        }
        if (!internal() && !account.equals(accountId)) {
            throw NotFoundException.of("account", account);
        }
        String r = requestedRegion != null ? requestedRegion : region;
        // a service token carries no region: the ledger keeps one region, global (decision 25)
        return AccountScope.of(account, r != null ? r : DEFAULT_REGION, subject);
    }

    /**
     * The scopes of a read over several accounts: a person's own account (naming any other is a
     * 404), or every account a console service that may reach accounts names, at least one.
     */
    public List<AccountScope> scopes(List<String> requestedAccountIds, String requestedRegion) {
        require(RouteGroup.ACCOUNTS);
        List<String> named = requestedAccountIds == null ? List.of()
                : requestedAccountIds.stream().filter(a -> a != null && !a.isBlank()).distinct().toList();
        if (named.isEmpty()) {
            return List.of(scope(null, requestedRegion));
        }
        if (named.size() > MAX_ACCOUNTS) {
            throw new IllegalArgumentException("at most " + MAX_ACCOUNTS + " accounts in one read");
        }
        return named.stream().map(a -> scope(a, requestedRegion)).toList();
    }
}

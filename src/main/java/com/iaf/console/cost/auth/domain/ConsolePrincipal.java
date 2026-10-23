package com.iaf.console.cost.auth.domain;

import java.util.List;
import java.util.Objects;

import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

/**
 * Who calls: the claims of the console's internal JWT. A console account's caller carries
 * {@code acct}; the console's own services (the BFF acting on its own, the drift service, an
 * operator's tooling) carry {@code svc} and no account, and are {@link #internal()}: they may name
 * any account. {@code roles} are the managed role names of the person in the account.
 */
public record ConsolePrincipal(String subject, String accountId, String region, List<String> groups, List<String> roles, String service) {

    /** The exchange attribute the authentication filter stores the principal under. */
    public static final String ATTRIBUTE = "com.iaf.console.cost.principal";

    /** The most accounts one read may name (the organization view). */
    public static final int MAX_ACCOUNTS = 500;

    public ConsolePrincipal {
        Objects.requireNonNull(subject, "subject");
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** A principal that acts for no account: one of the console's own services. */
    public boolean internal() {
        return accountId == null;
    }

    /**
     * The scope of a read: the principal's own account, or the named account for an internal
     * principal. Another account's name answers 404, never 403, so ids cannot be probed.
     */
    public AccountScope scope(String requestedAccountId, String requestedRegion) {
        String account = requestedAccountId != null && !requestedAccountId.isBlank() ? requestedAccountId : accountId;
        if (account == null) {
            throw new IllegalArgumentException("accountId is required for a call that acts for no account");
        }
        if (!internal() && !account.equals(accountId)) {
            throw NotFoundException.of("account", account);
        }
        String r = requestedRegion != null ? requestedRegion : region;
        return AccountScope.of(account, r, subject);
    }

    /**
     * The scopes of a read over several accounts: a person's own account (naming any other is a
     * 404), or every account an internal principal names, at least one.
     */
    public List<AccountScope> scopes(List<String> requestedAccountIds, String requestedRegion) {
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

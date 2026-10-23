package com.iaf.console.search.auth.domain;

import java.util.List;
import java.util.Objects;

import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

/**
 * Who calls: the claims of the console's internal JWT. A console account's caller carries
 * {@code acct}; the console's own services carry {@code svc} and no account, and only those
 * {@link InternalCallers} lists are let in (hub decision 35): the BFF acting on its own is
 * {@link #internal()} and may name any account; a counting service (drift, cost) may name any
 * account in {@code /v1/counts} only. {@code roles} are the managed role names of the person in
 * the account.
 */
public record ConsolePrincipal(String subject, String accountId, String region, List<String> groups, List<String> roles, String service,
        Access access) {

    /** What a caller may reach: its own account, every account's counts, or everything. */
    public enum Access {
        ACCOUNT, COUNTS, SERVICE
    }

    /** The exchange attribute the authentication filter stores the principal under. */
    public static final String ATTRIBUTE = "com.iaf.console.search.principal";

    public ConsolePrincipal {
        Objects.requireNonNull(subject, "subject");
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
        Objects.requireNonNull(access, "access");
        if ((access == Access.ACCOUNT) != (accountId != null)) {
            throw new IllegalArgumentException("a person acts in an account, a service in none");
        }
    }

    /** A principal that acts for no account and may reach everything: one of the console's own services. */
    public boolean internal() {
        return access == Access.SERVICE;
    }

    /**
     * The scope of a read: the principal's own account, or the named account for an internal
     * principal. Another account's name answers 404, never 403, so ids cannot be probed.
     */
    public AccountScope scope(String requestedAccountId, String requestedRegion) {
        return scope(requestedAccountId, requestedRegion, internal());
    }

    /** The scope of a count: as {@link #scope}, and a counting service may name any account too. */
    public AccountScope countScope(String requestedAccountId, String requestedRegion) {
        return scope(requestedAccountId, requestedRegion, access != Access.ACCOUNT);
    }

    private AccountScope scope(String requestedAccountId, String requestedRegion, boolean anyAccount) {
        String account = requestedAccountId != null && !requestedAccountId.isBlank() ? requestedAccountId : accountId;
        if (!anyAccount && (account == null || !account.equals(accountId))) {
            throw account == null ? new NotFoundException("not found") : NotFoundException.of("account", account);
        }
        if (account == null) {
            throw new IllegalArgumentException("accountId is required for a call that acts for no account");
        }
        String r = requestedRegion != null ? requestedRegion : region;
        return AccountScope.of(account, r, subject);
    }
}

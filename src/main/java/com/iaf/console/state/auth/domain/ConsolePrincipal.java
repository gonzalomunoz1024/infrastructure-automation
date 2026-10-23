package com.iaf.console.state.auth.domain;

import java.util.List;
import java.util.Objects;

import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

/**
 * Who calls: the claims of the console's internal JWT, which the BFF signs: {@code sub}, {@code acct},
 * {@code region}, {@code groups}, {@code roles} (the managed roles) and {@code svc}. A console account's caller carries
 * {@code acct}; the console's own services (the indexer, the drift service, the BFF acting on its
 * own) carry {@code svc} and no account, and are {@link #internal()}: they may name any account.
 */
public record ConsolePrincipal(String subject, String accountId, String region, List<String> groups, List<String> roles, String service) {

    /** The exchange attribute the authentication filter stores the principal under. */
    public static final String ATTRIBUTE = "com.iaf.console.state.principal";

    public ConsolePrincipal {
        Objects.requireNonNull(subject, "subject");
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** The managed role of an account's admins, as the token's {@code roles} claim names it (hub decision 21). */
    public static final String ACCOUNT_ADMIN = "account-admin";

    /** A principal that acts for no account: one of the console's own services. */
    public boolean internal() {
        return accountId == null;
    }

    /**
     * Who a command is recorded as: the token's subject, or for an internal caller acting for a
     * person, the person it names. A person's token never names anyone else.
     */
    public String actor(String claimed) {
        return internal() && claimed != null && !claimed.isBlank() ? claimed : subject;
    }

    /**
     * Whether {@code reveal=sensitive} is honored for this caller: an internal principal, or a person
     * whose {@code roles} hold {@code account-admin}. A group name never decides, and a token without
     * roles has none, so it is answered masked.
     */
    public boolean mayReveal() {
        return internal() || roles.contains(ACCOUNT_ADMIN);
    }

    /** The managed role of an account's auditors, as the token's {@code roles} claim names it. */
    public static final String AUDITOR = "auditor";

    /** Whether the caller may take an account's evidence pack: an internal principal, an account admin or an auditor. */
    public boolean mayAudit() {
        return internal() || roles.contains(ACCOUNT_ADMIN) || roles.contains(AUDITOR);
    }

    /**
     * The scope of a read or a command: the principal's own account, or the named account for an
     * internal principal. Another account's name answers 404, never 403, so ids cannot be probed.
     */
    public AccountScope scope(String requestedAccountId, String requestedRegion) {
        String account = requestedAccountId != null ? requestedAccountId : accountId;
        if (account == null) {
            throw new IllegalArgumentException("accountId is required for a call that acts for no account");
        }
        if (!internal() && !account.equals(accountId)) {
            throw NotFoundException.of("account", account);
        }
        String r = requestedRegion != null ? requestedRegion : region;
        return AccountScope.of(account, r, subject);
    }
}

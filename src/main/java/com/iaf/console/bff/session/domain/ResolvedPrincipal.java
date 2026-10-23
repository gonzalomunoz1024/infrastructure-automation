package com.iaf.console.bff.session.domain;

import java.util.List;
import java.util.Objects;

/** The identity service's answer to a sign-in: the console user and the accounts they may use. */
public record ResolvedPrincipal(Principal user, List<AccountMembership> accounts) {

    public ResolvedPrincipal {
        Objects.requireNonNull(user, "user");
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }

    public boolean hasAccounts() {
        return !accounts.isEmpty();
    }
}

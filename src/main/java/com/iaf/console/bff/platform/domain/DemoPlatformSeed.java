package com.iaf.console.bff.platform.domain;

import java.util.List;
import java.util.Optional;

/** What the embedded demo platform seeded: the credentials of the demo accounts and the console-internal one. */
public record DemoPlatformSeed(List<PlatformCredential> accounts, PlatformCredential internal) {

    public DemoPlatformSeed {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }

    public Optional<PlatformCredential> account(String accountId) {
        return accounts.stream().filter(c -> accountId.equals(c.accountId())).findFirst();
    }
}

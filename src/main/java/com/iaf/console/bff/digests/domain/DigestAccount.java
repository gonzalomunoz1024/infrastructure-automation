package com.iaf.console.bff.digests.domain;

import java.util.Objects;

/** An account a digest is written for: its id, its name and the region its reads go to (its default region, else {@code global}). */
public record DigestAccount(String accountId, String name, String region) {

    public static final String NO_REGION = "global";

    public DigestAccount {
        Objects.requireNonNull(accountId, "accountId");
        region = region == null || region.isBlank() ? NO_REGION : region;
    }
}

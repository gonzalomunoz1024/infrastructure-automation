package com.iaf.console.identity.quotas.domain;

import java.time.Instant;

/** A limit set on an account for one key. */
public record QuotaOverride(String accountId, String key, int limit, Integer burst, long version, Instant updatedAt) {

    public QuotaOverride changed(int newLimit, Integer newBurst, Instant now) {
        return new QuotaOverride(accountId, key, newLimit, newBurst, version + 1, now);
    }
}

package com.iaf.console.identity.accounts.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A console account: the tenant every resource, user, group, role, policy, key and quota belongs
 * to. {@code version} grows by one per change and travels in the events.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Account(String accountId, String name, AccountStatus status, String owningTeam, String defaultRegion, String platformServiceAccountId,
        long version, Instant createdAt, Instant updatedAt) {

    public Account {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(owningTeam, "owningTeam");
    }

    public static Account create(String accountId, String name, String owningTeam, String defaultRegion, String platformServiceAccountId, Instant now) {
        return new Account(accountId, name, AccountStatus.active, owningTeam, defaultRegion, platformServiceAccountId, 1, now, now);
    }

    public boolean isActive() {
        return status == AccountStatus.active;
    }

    /** The account after a change: the fields given replace the current ones, the version grows. */
    public Account changed(String newName, AccountStatus newStatus, String newDefaultRegion, Instant now) {
        return new Account(accountId, newName == null ? name : newName, newStatus == null ? status : newStatus, owningTeam,
                newDefaultRegion == null ? defaultRegion : newDefaultRegion, platformServiceAccountId, version + 1, createdAt, now);
    }

    public Account withPlatformServiceAccount(String id, Instant now) {
        return new Account(accountId, name, status, owningTeam, defaultRegion, id, version + 1, createdAt, now);
    }
}

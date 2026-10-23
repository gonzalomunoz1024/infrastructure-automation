package com.iaf.console.identity.accounts.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A console account: the tenant every resource, user, group, role, policy, key and quota belongs
 * to. {@code version} grows by one per change and travels in the events.
 *
 * <p>{@code costCentre} is what the cost service charges the account's spend to (it falls back to
 * {@code owningTeam} when there is none); it is trimmed, and a blank one is none. A closed account
 * carries {@code closedAt} and {@code closeReason}; closing is terminal. {@code owningTeamMissing}
 * is true while the nightly group sync finds no identity provider group named {@code owningTeam},
 * and absent otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Account(String accountId, String name, AccountStatus status, String owningTeam, String defaultRegion, String platformServiceAccountId,
        long version, Instant createdAt, Instant updatedAt, String costCentre, Instant closedAt, String closeReason, Boolean owningTeamMissing) {

    public Account {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(owningTeam, "owningTeam");
        costCentre = costCentre == null || costCentre.isBlank() ? null : costCentre.trim();
        owningTeamMissing = Boolean.TRUE.equals(owningTeamMissing) ? Boolean.TRUE : null;
    }

    public static Account create(String accountId, String name, String owningTeam, String defaultRegion, String platformServiceAccountId, Instant now) {
        return create(accountId, name, owningTeam, defaultRegion, platformServiceAccountId, null, now);
    }

    public static Account create(String accountId, String name, String owningTeam, String defaultRegion, String platformServiceAccountId, String costCentre,
            Instant now) {
        return new Account(accountId, name, AccountStatus.active, owningTeam, defaultRegion, platformServiceAccountId, 1, now, now, costCentre, null, null,
                null);
    }

    public boolean isActive() {
        return status == AccountStatus.active;
    }

    /** {@code status} says it; the account as served, stored and sent has no field of its own for it. */
    @JsonIgnore
    public boolean isClosed() {
        return status == AccountStatus.closed;
    }

    public boolean owningTeamIsMissing() {
        return Boolean.TRUE.equals(owningTeamMissing);
    }

    /** The account after a change: the fields given replace the current ones, the version grows. */
    public Account changed(String newName, AccountStatus newStatus, String newDefaultRegion, Instant now) {
        return changed(newName, newStatus, newDefaultRegion, null, now);
    }

    /** The same with the cost centre: null keeps it, a blank one clears it. */
    public Account changed(String newName, AccountStatus newStatus, String newDefaultRegion, String newCostCentre, Instant now) {
        return new Account(accountId, newName == null ? name : newName, newStatus == null ? status : newStatus, owningTeam,
                newDefaultRegion == null ? defaultRegion : newDefaultRegion, platformServiceAccountId, version + 1, createdAt, now,
                newCostCentre == null ? costCentre : newCostCentre, closedAt, closeReason, owningTeamMissing);
    }

    /** The account closed for {@code reason}: terminal, and every call in it is refused from now on. */
    public Account closed(String reason, Instant now) {
        return new Account(accountId, name, AccountStatus.closed, owningTeam, defaultRegion, platformServiceAccountId, version + 1, createdAt, now, costCentre,
                now, reason, owningTeamMissing);
    }

    /** The account with what the group sync found about its owning team. */
    public Account withOwningTeamMissing(boolean missing, Instant now) {
        return new Account(accountId, name, status, owningTeam, defaultRegion, platformServiceAccountId, version + 1, createdAt, now, costCentre, closedAt,
                closeReason, missing);
    }

    public Account withPlatformServiceAccount(String id, Instant now) {
        return new Account(accountId, name, status, owningTeam, defaultRegion, id, version + 1, createdAt, now, costCentre, closedAt, closeReason,
                owningTeamMissing);
    }
}

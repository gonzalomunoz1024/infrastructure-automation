package com.iaf.console.bff.session.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A signed-in user in one account and region. The session id is the cookie's value; the user's
 * accounts are the ones the identity service answered at sign-in; {@code authTime} is when the
 * identity provider last saw the user type a credential, which the step-up policy reads.
 */
public record Session(
        String id,
        Principal user,
        List<AccountMembership> accounts,
        String accountId,
        String region,
        Instant createdAt,
        Instant authTime,
        Instant lastSeenAt,
        Instant expiresAt,
        String issuer,
        String subject) {

    public Session {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        authTime = authTime == null ? createdAt : authTime;
        lastSeenAt = lastSeenAt == null ? createdAt : lastSeenAt;
        // a compact constructor assigns the fields after its body, so the check reads the parameter
        List<AccountMembership> memberships = accounts;
        if (memberships.stream().noneMatch(a -> a.accountId().equals(accountId))) {
            throw new IllegalArgumentException("the session's account " + accountId + " is not one of the user's accounts");
        }
    }

    /** The membership of the current account. */
    public Optional<AccountMembership> membership(String otherAccountId) {
        return accounts.stream().filter(a -> a.accountId().equals(otherAccountId)).findFirst();
    }

    public Optional<AccountMembership> account() {
        return membership(accountId);
    }

    /** The current account, which the constructor guarantees. */
    public AccountMembership currentAccount() {
        return account().orElseThrow();
    }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public Session switchedTo(String otherAccountId, String otherRegion) {
        return new Session(id, user, accounts, otherAccountId, otherRegion, createdAt, authTime, lastSeenAt, expiresAt, issuer, subject);
    }

    public Session withAccounts(List<AccountMembership> refreshed) {
        return new Session(id, user, refreshed, accountId, region, createdAt, authTime, lastSeenAt, expiresAt, issuer, subject);
    }

    public Session seenAt(Instant now) {
        return new Session(id, user, accounts, accountId, region, createdAt, authTime, now, expiresAt, issuer, subject);
    }

    public Session reauthenticatedAt(Instant when) {
        return new Session(id, user, accounts, accountId, region, createdAt, when, lastSeenAt, expiresAt, issuer, subject);
    }

    public Session expiringAt(Instant when) {
        return new Session(id, user, accounts, accountId, region, createdAt, authTime, lastSeenAt, when, issuer, subject);
    }
}

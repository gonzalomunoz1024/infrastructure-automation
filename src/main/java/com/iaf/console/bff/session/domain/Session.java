package com.iaf.console.bff.session.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A signed-in user in one account and region. The session id is the cookie's value; the user's
 * accounts are the ones the identity service answered, at sign-in and at every re-read since
 * ({@code rolesResolvedAt}); {@code rolesUntil} is when they must be read again, at the latest
 * after the re-read interval and never after the earliest time-boxed role ends, and no token may
 * carry them past it. {@code idpGroups} are the identity provider's groups the sign-in named, which
 * the re-read sends again and a step-up reads again (null on a session stored before they were
 * kept, which a re-read ends); {@code organizationAdmin} was decided from them. {@code authTime} is
 * when the identity provider last saw the user type a credential, which the step-up policy reads.
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
        String subject,
        List<String> idpGroups,
        Instant rolesResolvedAt,
        Instant rolesUntil,
        boolean organizationAdmin) {

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
        idpGroups = idpGroups == null ? null : List.copyOf(idpGroups);
        rolesResolvedAt = rolesResolvedAt == null ? createdAt : rolesResolvedAt;
        // a compact constructor assigns the fields after its body, so the check reads the parameter
        List<AccountMembership> memberships = accounts;
        if (memberships.stream().noneMatch(a -> a.accountId().equals(accountId))) {
            throw new IllegalArgumentException("the session's account " + accountId + " is not one of the user's accounts");
        }
    }

    /**
     * A session of someone who is no organization admin, whose memberships were read at its
     * creation and are due again now (tests, and records from before the re-reads).
     */
    public Session(String id, Principal user, List<AccountMembership> accounts, String accountId, String region, Instant createdAt,
            Instant authTime, Instant lastSeenAt, Instant expiresAt, String issuer, String subject) {
        this(id, user, accounts, accountId, region, createdAt, authTime, lastSeenAt, expiresAt, issuer, subject, null, createdAt, null, false);
    }

    /** A session of someone who is no organization admin (records from before organization admins were kept). */
    public Session(String id, Principal user, List<AccountMembership> accounts, String accountId, String region, Instant createdAt,
            Instant authTime, Instant lastSeenAt, Instant expiresAt, String issuer, String subject, List<String> idpGroups,
            Instant rolesResolvedAt, Instant rolesUntil) {
        this(id, user, accounts, accountId, region, createdAt, authTime, lastSeenAt, expiresAt, issuer, subject, idpGroups, rolesResolvedAt,
                rolesUntil, false);
    }

    /** When the earliest time-boxed role of these memberships ends; null when none is held. */
    public static Instant earliestElevationEnd(List<AccountMembership> accounts) {
        Instant earliest = null;
        for (AccountMembership a : accounts) {
            if (a.elevatedUntil() != null && (earliest == null || a.elevatedUntil().isBefore(earliest))) {
                earliest = a.elevatedUntil();
            }
        }
        return earliest;
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

    /** Whether the memberships must be read again before the session is used: unknown, or at or past {@code rolesUntil}. */
    public boolean rolesDue(Instant now) {
        return rolesUntil == null || !now.isBefore(rolesUntil);
    }

    /** The same session under another id: a store keeps it under the id's hash. */
    public Session withId(String otherId) {
        return new Session(otherId, user, accounts, accountId, region, createdAt, authTime, lastSeenAt, expiresAt, issuer, subject, idpGroups,
                rolesResolvedAt, rolesUntil, organizationAdmin);
    }

    public Session switchedTo(String otherAccountId, String otherRegion) {
        return new Session(id, user, accounts, otherAccountId, otherRegion, createdAt, authTime, lastSeenAt, expiresAt, issuer, subject, idpGroups,
                rolesResolvedAt, rolesUntil, organizationAdmin);
    }

    /** The memberships as read again at {@code resolvedAt}, valid until {@code until}; the person as the identity service now has them. */
    public Session withAccounts(Principal resolvedUser, List<AccountMembership> refreshed, Instant resolvedAt, Instant until) {
        return new Session(id, resolvedUser, refreshed, accountId, region, createdAt, authTime, lastSeenAt, expiresAt, issuer, subject, idpGroups,
                resolvedAt, until, organizationAdmin);
    }

    /** Other memberships, read no later than the current ones were (tests). */
    public Session withAccounts(List<AccountMembership> refreshed) {
        return new Session(id, user, refreshed, accountId, region, createdAt, authTime, lastSeenAt, expiresAt, issuer, subject, idpGroups,
                rolesResolvedAt, rolesUntil, organizationAdmin);
    }

    public Session seenAt(Instant now) {
        return new Session(id, user, accounts, accountId, region, createdAt, authTime, now, expiresAt, issuer, subject, idpGroups, rolesResolvedAt,
                rolesUntil, organizationAdmin);
    }

    public Session reauthenticatedAt(Instant when) {
        return new Session(id, user, accounts, accountId, region, createdAt, when, lastSeenAt, expiresAt, issuer, subject, idpGroups, rolesResolvedAt,
                rolesUntil, organizationAdmin);
    }

    /** The identity provider's groups of a new sign-in (a step-up), and whether they make the person an organization admin. */
    public Session withIdpGroups(List<String> groups, boolean admin) {
        return new Session(id, user, accounts, accountId, region, createdAt, authTime, lastSeenAt, expiresAt, issuer, subject, groups, rolesResolvedAt,
                rolesUntil, admin);
    }

    public Session expiringAt(Instant when) {
        return new Session(id, user, accounts, accountId, region, createdAt, authTime, lastSeenAt, when, issuer, subject, idpGroups, rolesResolvedAt,
                rolesUntil, organizationAdmin);
    }
}

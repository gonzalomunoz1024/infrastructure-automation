package com.iaf.console.bff.clients.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.wfrn.Ulid;

/**
 * A CLI or SDK signed in with the device grant (decision 31): the person (at the identity provider
 * and in the console), their memberships as last read, the client, the account and region the
 * grant was approved for, when the approving browser sign-in happened, when it opened, was last
 * refreshed and ends (absolutely, and when idle), the SHA-256 of the current refresh token and of
 * the ones it rotated away (a rotated one presented again ends the session), whether and why it
 * was revoked, and where the client called from. {@code version} grows with every change, so a
 * store replaces a session only as it was read.
 */
public record ApiSession(
        String id,
        String clientId,
        String issuer,
        String subject,
        Principal user,
        List<AccountMembership> accounts,
        String accountId,
        String region,
        Instant authTime,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant idleExpiresAt,
        String refreshHash,
        List<String> rotatedHashes,
        Instant revokedAt,
        String revokedBecause,
        String userAgent,
        String ip,
        long version) {

    /** The prefix of an API session's id; a lowercase ULID follows. */
    public static final String ID_PREFIX = "aps_";
    /** How many rotated refresh tokens are remembered for reuse detection (a day of 15-minute refreshes is 96). */
    public static final int MAX_ROTATED = 100;

    public ApiSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(authTime, "authTime");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(refreshHash, "refreshHash");
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        rotatedHashes = rotatedHashes == null ? List.of() : List.copyOf(rotatedHashes);
        lastUsedAt = lastUsedAt == null ? createdAt : lastUsedAt;
        idleExpiresAt = idleExpiresAt == null ? expiresAt : idleExpiresAt;
    }

    /** A new API session id: {@code aps_} and a lowercase ULID. */
    public static String newId() {
        return ID_PREFIX + Ulid.next().toLowerCase(Locale.ROOT);
    }

    /**
     * The session a redeemed grant opens, under the id the grant names: as its approver was, with
     * the first refresh token's hash, called from where the redeeming request came from; it ends
     * {@code ttl} after the approval, or {@code idle} after the last refresh.
     */
    public static ApiSession open(DeviceGrant grant, String refreshHash, RequestedFrom from, Instant now, Duration ttl, Duration idle) {
        DeviceGrant.Approver by = Objects.requireNonNull(grant.approver(), "an open session needs the grant's approver");
        String id = Objects.requireNonNull(grant.apiSessionId(), "an open session needs the id the grant was redeemed for");
        Instant expiresAt = grant.decidedAt().plus(ttl);
        return new ApiSession(id, grant.clientId(), by.issuer(), by.subject(), by.user(), by.accounts(), by.accountId(), by.region(), by.authTime(),
                now, now, expiresAt, earlier(expiresAt, now.plus(idle)), refreshHash, List.of(), null, null, from.userAgent(), from.ip(), 1);
    }

    /** Whether a string is an API session id as this service mints them: {@code aps_} and a lowercase ULID. */
    public static boolean isId(String id) {
        return id != null && id.startsWith(ID_PREFIX) && Ulid.isValid(id.substring(ID_PREFIX.length()));
    }

    /** Neither revoked nor past its end, absolute or idle. */
    public boolean openAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt) && now.isBefore(idleExpiresAt);
    }

    /** Why the session no longer holds at {@code now}, for the log; null while it is open. */
    public String endedAt(Instant now) {
        if (revokedAt != null) {
            return "revoked (" + (revokedBecause == null ? "no reason" : revokedBecause) + ")";
        }
        if (!now.isBefore(expiresAt)) {
            return "expired";
        }
        return now.isBefore(idleExpiresAt) ? null : "idle too long";
    }

    /** Whether a refresh token's hash is one this session rotated away. */
    public boolean rotatedAway(String hash) {
        boolean found = false;
        for (String old : rotatedHashes) {
            // every one is compared, so the time says nothing of which matched
            found |= Secrets.sameHash(old, hash);
        }
        return found;
    }

    public Optional<AccountMembership> membership(String otherAccountId) {
        return accounts.stream().filter(a -> a.accountId().equals(otherAccountId)).findFirst();
    }

    /**
     * The session after a refresh: the new refresh token's hash, the old one remembered, the
     * person and memberships as the identity service answered them again (the account it was
     * approved for, or the first one when the person left it), used now and idle from now.
     */
    public ApiSession rotated(String newHash, Principal person, List<AccountMembership> memberships, Instant now, Duration idle) {
        if (memberships == null || memberships.isEmpty()) {
            throw new IllegalArgumentException("a refreshed API session needs one membership at least");
        }
        String nextAccount = accountId;
        String nextRegion = region;
        if (memberships.stream().noneMatch(a -> a.accountId().equals(accountId))) {
            AccountMembership first = memberships.get(0);
            nextAccount = first.accountId();
            nextRegion = first.defaultRegion() == null || first.defaultRegion().isBlank() ? "global" : first.defaultRegion();
        }
        List<String> rotated = new ArrayList<>(rotatedHashes);
        rotated.add(refreshHash);
        if (rotated.size() > MAX_ROTATED) {
            rotated = rotated.subList(rotated.size() - MAX_ROTATED, rotated.size());
        }
        return new ApiSession(id, clientId, issuer, subject, person, memberships, nextAccount, nextRegion, authTime, createdAt, now, expiresAt,
                earlier(expiresAt, now.plus(idle)), newHash, rotated, revokedAt, revokedBecause, userAgent, ip, version + 1);
    }

    /** The session revoked now, for a reason the log keeps; a revoked session stays as it was. */
    public ApiSession revoked(Instant now, String because) {
        if (revokedAt != null) {
            return this;
        }
        return new ApiSession(id, clientId, issuer, subject, user, accounts, accountId, region, authTime, createdAt, lastUsedAt, expiresAt, idleExpiresAt,
                refreshHash, rotatedHashes, now, because, userAgent, ip, version + 1);
    }

    /**
     * The console session of one bearer call: this session's person and memberships in the
     * account and region the call names (which the caller checked), signed in when the token's
     * {@code auth_time} says, so a step-up reads the approving sign-in. Its id is this session's.
     */
    public Session callSession(String inAccount, String inRegion, Instant signedInAt, Instant now) {
        return new Session(id, user, accounts, inAccount, inRegion, createdAt, signedInAt, now, expiresAt, issuer, subject);
    }

    /** When a store may forget the session: its end, absolute or idle, whichever comes first. */
    public Instant purgeAt() {
        return earlier(expiresAt, idleExpiresAt);
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}

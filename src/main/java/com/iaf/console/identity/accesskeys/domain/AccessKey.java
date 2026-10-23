package com.iaf.console.identity.accesskeys.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An access key: the public part ({@code ck_} and a ULID) and the hashes of its secret parts. A
 * rotation keeps the previous hash working until {@code graceUntil}. The status the contract
 * serves is computed from the record and the clock, so an expired key reads as expired without a
 * sweep.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccessKey(String keyId, @JsonIgnore String accountId, String userId, String description, @JsonIgnore String secretHash,
        @JsonIgnore String previousSecretHash, Instant graceUntil, Instant revokedAt, Instant createdAt, Instant expiresAt, Instant lastUsedAt, long version) {

    /** How long the previous secret part keeps working after a rotation. */
    public static final Duration ROTATION_GRACE = Duration.ofMinutes(10);

    public AccessKey {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(userId, "userId");
    }

    public static AccessKey create(String keyId, String accountId, String userId, String description, String secretPart, Instant expiresAt, Instant now) {
        return new AccessKey(keyId, accountId, userId, description, Secrets.hash(keyId, secretPart), null, null, null, now, expiresAt, null, 1);
    }

    public AccessKeyStatus status(Instant now) {
        if (revokedAt != null) {
            return AccessKeyStatus.revoked;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return AccessKeyStatus.expired;
        }
        if (graceUntil != null && now.isBefore(graceUntil)) {
            return AccessKeyStatus.rotating;
        }
        return AccessKeyStatus.active;
    }

    /** Active or rotating: a key that still opens the door. */
    public boolean usable(Instant now) {
        AccessKeyStatus s = status(now);
        return s == AccessKeyStatus.active || s == AccessKeyStatus.rotating;
    }

    public AccessKey rotated(String newSecretPart, Instant now) {
        return new AccessKey(keyId, accountId, userId, description, Secrets.hash(keyId, newSecretPart), secretHash, now.plus(ROTATION_GRACE), revokedAt,
                createdAt, expiresAt, lastUsedAt, version + 1);
    }

    public AccessKey revoked(Instant now) {
        return new AccessKey(keyId, accountId, userId, description, secretHash, null, null, now, createdAt, expiresAt, lastUsedAt, version + 1);
    }

    public AccessKey used(Instant now) {
        return new AccessKey(keyId, accountId, userId, description, secretHash, previousSecretHash, graceUntil, revokedAt, createdAt, expiresAt, now, version);
    }

    /** Whether a secret part opens this key now: the current hash, or the previous one within the grace. */
    public boolean accepts(String secretPart, Instant now) {
        if (!usable(now)) {
            return false;
        }
        if (Secrets.matches(secretHash, keyId, secretPart)) {
            return true;
        }
        return previousSecretHash != null && graceUntil != null && now.isBefore(graceUntil) && Secrets.matches(previousSecretHash, keyId, secretPart);
    }

    public AccessKeyView view(Instant now) {
        return new AccessKeyView(keyId, userId, description, status(now), createdAt, expiresAt, lastUsedAt, version);
    }

    public AccessKeyCreated created(String secretPart, Instant now) {
        return new AccessKeyCreated(keyId, userId, description, status(now), createdAt, expiresAt, lastUsedAt, version, secretPart,
                graceUntil != null && now.isBefore(graceUntil) ? graceUntil : null);
    }
}

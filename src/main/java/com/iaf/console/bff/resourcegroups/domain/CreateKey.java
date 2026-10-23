package com.iaf.console.bff.resourcegroups.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The Idempotency-Key a group was created with, who sent it, the fingerprint of that create's
 * body and when: for a day, a create with the same key by the same person answers that group
 * again, or a 409 when its body differs.
 */
public record CreateKey(String key, String principal, String fingerprint, Instant at) {

    /** How long a key answers the group its create made. */
    public static final Duration WINDOW = Duration.ofHours(24);
    /** The longest key the console's APIs take. */
    public static final int MAX_LENGTH = 255;

    public CreateKey {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(at, "at");
    }

    /** The key of a create, or null when the request carries none. */
    public static CreateKey of(String header, String principal, String fingerprint, Instant at) {
        return header == null || header.isBlank() ? null : new CreateKey(header.strip(), principal, fingerprint, at);
    }

    /** Whether the key still answers a create repeated at {@code now}. */
    public boolean answers(Instant now) {
        return !at.plus(WINDOW).isBefore(now);
    }
}

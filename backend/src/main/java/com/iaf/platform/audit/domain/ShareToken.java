package com.iaf.platform.audit.domain;

import java.time.Instant;

/** A read-only link to one record, valid until {@code expiresAt}. */
public record ShareToken(String token, String jobId, Instant expiresAt) {

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}

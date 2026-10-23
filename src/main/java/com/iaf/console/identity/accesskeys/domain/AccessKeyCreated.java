package com.iaf.console.identity.accesskeys.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The answer of a create or a rotation: the key with its secret part, shown this once. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccessKeyCreated(String keyId, String userId, String description, AccessKeyStatus status, Instant createdAt, Instant expiresAt,
        Instant lastUsedAt, long version, String secretPart, Instant graceUntil) {
}

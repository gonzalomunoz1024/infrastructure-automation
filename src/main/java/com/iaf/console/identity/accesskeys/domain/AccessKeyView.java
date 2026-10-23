package com.iaf.console.identity.accesskeys.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/** An access key as the contract serves it: id and state, never a secret. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccessKeyView(String keyId, String userId, String description, AccessKeyStatus status, Instant createdAt, Instant expiresAt, Instant lastUsedAt,
        long version) {
}

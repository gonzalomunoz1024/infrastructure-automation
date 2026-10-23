package com.iaf.console.identity.accesskeys.application;

import java.time.Instant;

/** The body of {@code POST /v1/accounts/{accountId}/access-keys}. */
public record CreateAccessKey(String userId, String description, Instant expiresAt) {
}

package com.iaf.console.identity.common.idempotency;

import java.time.Instant;

/**
 * What one idempotent call left behind: the hash of its body, and, once it ended, the status,
 * content type and body it answered. {@code bodyHash} and the key identify a retry; a different
 * hash under the same key is a conflict.
 */
public record IdempotencyRecord(String scope, String bodyHash, Integer status, String contentType, byte[] body, Instant createdAt, Instant expiresAt) {

    public boolean completed() {
        return status != null;
    }

    public IdempotencyRecord completed(int responseStatus, String responseContentType, byte[] responseBody) {
        return new IdempotencyRecord(scope, bodyHash, responseStatus, responseContentType, responseBody, createdAt, expiresAt);
    }
}

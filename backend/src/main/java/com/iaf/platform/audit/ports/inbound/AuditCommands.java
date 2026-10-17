package com.iaf.platform.audit.ports.inbound;

import java.time.Instant;

import reactor.core.publisher.Mono;

/**
 * Write side of audit. Evidence itself is written by the event listener, never through a
 * command; these are the two things a person does to a record: seal it (also scheduled) and
 * share it. Both answer with what docs/api.md promises rather than a bare CommandAccepted.
 */
public interface AuditCommands {

    /** One sealing run: {@code signature} signs the ledger of every record sealed in it. */
    record SealResult(Instant sealedAt, String signature, String keyId, int sealed) {
    }

    record ShareLink(String url, Instant expiresAt) {
    }

    /** Sign the latest hash of every closed, unsealed record. */
    Mono<SealResult> seal();

    /** A read-only link to one record, valid for seven days. */
    Mono<ShareLink> share(String jobId);
}

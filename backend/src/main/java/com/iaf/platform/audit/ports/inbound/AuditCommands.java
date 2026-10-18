package com.iaf.platform.audit.ports.inbound;

import java.time.Instant;

import reactor.core.publisher.Mono;

/**
 * Write side of audit. Evidence itself is written by the event listener, never through a
 * command; these are the two things a person does to a record: seal it (also scheduled) and
 * share it. Both answer with what docs/api.md promises rather than a bare CommandAccepted.
 */
public interface AuditCommands {

    /**
     * One sealing run: {@code sealed} closed records got their final seal, {@code checkpointed}
     * open ones (and the platform stream) a signature over their head; {@code signature} signs the
     * ledger of every head signed in the run.
     */
    record SealResult(Instant sealedAt, String signature, String keyId, int sealed, int checkpointed) {

        public SealResult(Instant sealedAt, String signature, String keyId, int sealed) {
            this(sealedAt, signature, keyId, sealed, 0);
        }
    }

    record ShareLink(String url, Instant expiresAt) {
    }

    /** Sign the latest hash of every record: closed ones are sealed for good, open ones checkpointed. */
    Mono<SealResult> seal();

    /** A read-only link to one record, valid for seven days. */
    Mono<ShareLink> share(String jobId);
}

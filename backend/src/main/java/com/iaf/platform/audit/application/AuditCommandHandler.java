package com.iaf.platform.audit.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.ports.inbound.AuditCommands;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.identity.application.CurrentIdentity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/** Seals closed records and checkpoints open ones (on a schedule and on demand), and mints share links. */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class AuditCommandHandler implements AuditCommands {

    static final Duration SHARE_FOR = Duration.ofDays(7);

    private final EvidenceStore store;
    private final AuditSigner signer;
    private final EventBus events;
    private final Clock clock;
    private final ObjectMapper mapper;

    @Scheduled(fixedDelayString = "${platform.audit.seal-every}")
    public void sealOnSchedule() {
        try {
            SealResult result = sealAll(Identity.SYSTEM.id());
            if (result.sealed() > 0 || result.checkpointed() > 0) {
                log.info("sealed {} evidence records and checkpointed {} open ones (keyId={})", result.sealed(), result.checkpointed(), result.keyId());
            }
        } catch (RuntimeException e) {
            log.warn("sealing run failed: {}", e.toString());
        }
    }

    @Override
    public Mono<SealResult> seal() {
        return CurrentIdentity.current().map(who -> sealAll(who.id()));
    }

    /**
     * Signs the head of every record. A closed, unsealed record gets its final seal
     * {@code {at, signature(latestHash), keyId, seq, hash}}; an open one (a running job, the
     * platform stream) whose head moved since its last checkpoint gets a checkpoint of the same
     * shape and keeps accepting entries. The run's own signature covers the ledger
     * "jobId latestHash" of every head it signed.
     */
    SealResult sealAll(String actor) {
        Instant now = clock.instant();
        StringBuilder ledger = new StringBuilder();
        int sealed = 0;
        int checkpointed = 0;
        boolean checkpointsRefused = false;
        for (EvidenceRecord record : store.records(EvidenceFilter.ALL)) {
            if (record.sealed() != null || record.entries().isEmpty()) {
                continue;
            }
            EvidenceRecord.Seal seal = new EvidenceRecord.Seal(now, signer.sign(record.latestHash()), signer.keyId(), record.entries().size(),
                    record.latestHash());
            if (record.closed() && !record.platform()) {
                store.seal(record.jobId(), seal);
                events.publish("audit.sealed", "audit", record.jobId(), actor,
                        mapper.createObjectNode().put("jobId", record.jobId()).put("keyId", signer.keyId()));
                sealed++;
            } else {
                EvidenceRecord.Seal last = record.latestCheckpoint();
                if (last != null && record.latestHash().equals(last.hash())) {
                    continue;
                }
                try {
                    store.checkpoint(record.jobId(), seal);
                } catch (UnsupportedOperationException e) {
                    if (!checkpointsRefused) {
                        log.warn("open records are not checkpointed: {}", e.getMessage());
                    }
                    checkpointsRefused = true;
                    continue;
                }
                checkpointed++;
            }
            ledger.append(record.jobId()).append(' ').append(record.latestHash()).append('\n');
        }
        return new SealResult(now, signer.sign(ledger.toString()), signer.keyId(), sealed, checkpointed);
    }

    @Override
    public Mono<ShareLink> share(String jobId) {
        return Mono.fromSupplier(() -> {
            store.record(jobId).orElseThrow(() -> new NotFoundException("evidence record", jobId));
            ShareToken token = store.shareToken(jobId, clock.instant().plus(SHARE_FOR));
            return new ShareLink("/api/v1/audit/shared/" + token.token(), token.expiresAt());
        });
    }
}

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

/** Seals closed records (on a schedule and on demand) and mints share links. */
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
            if (result.sealed() > 0) {
                log.info("sealed {} evidence records (keyId={})", result.sealed(), result.keyId());
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
     * Every closed, unsealed record gets {@code sealed = {at, signature(latestHash), keyId}}; the
     * run's own signature covers the ledger "jobId latestHash" of what it sealed.
     */
    SealResult sealAll(String actor) {
        Instant now = clock.instant();
        StringBuilder ledger = new StringBuilder();
        int sealed = 0;
        for (EvidenceRecord record : store.records(EvidenceFilter.ALL)) {
            if (!record.closed() || record.sealed() != null) {
                continue;
            }
            String signature = signer.sign(record.latestHash());
            store.seal(record.jobId(), new EvidenceRecord.Seal(now, signature, signer.keyId()));
            events.publish("audit.sealed", "audit", record.jobId(), actor,
                    mapper.createObjectNode().put("jobId", record.jobId()).put("keyId", signer.keyId()));
            ledger.append(record.jobId()).append(' ').append(record.latestHash()).append('\n');
            sealed++;
        }
        return new SealResult(now, signer.sign(ledger.toString()), signer.keyId(), sealed);
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

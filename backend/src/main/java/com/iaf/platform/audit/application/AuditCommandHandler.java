package com.iaf.platform.audit.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.ports.inbound.AuditCommands;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.audit.ports.outbound.EvidenceStore.Unsealed;
import com.iaf.platform.audit.ports.outbound.RecordOwners;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.identity.application.CurrentIdentity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Seals closed records and checkpoints open ones (on a schedule, and on demand by an admin), and
 * mints share links: a job's record is shared by its requester, a member of the teams it ran for
 * (the requester's teams and the workflow's owner team, {@link RecordOwners}) or an admin, and
 * every link is published as {@code audit.shared} (the record, who, until when; never the token).
 * Both touch the store, so they run on boundedElastic.
 */
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
    private final RecordOwners owners;

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
        return CurrentIdentity.current().flatMap(who -> {
            if (!who.admin()) {
                return Mono.error(new ForbiddenException(who.name() + " may not seal evidence: only admins seal on demand,"
                        + " and the platform seals every record on its own schedule"));
            }
            return Mono.fromCallable(() -> sealAll(who.id())).subscribeOn(Schedulers.boundedElastic());
        });
    }

    /**
     * Signs the head of every record. A closed, unsealed record gets its final seal
     * {@code {at, signature(latestHash), keyId, seq, hash}}; an open one (a running job, the
     * platform stream) whose head moved since its last checkpoint gets a checkpoint of the same
     * shape and keeps accepting entries. The run's own signature covers the ledger
     * "jobId latestHash" of every head it signed. Only the records still taking signatures are
     * read ({@link EvidenceStore#unsealed()}), never a sealed one or any entry. The run signs
     * everything with the one key it takes at the start.
     */
    SealResult sealAll(String actor) {
        AuditSigner.Key key = signer.key();
        Instant now = clock.instant();
        StringBuilder ledger = new StringBuilder();
        int sealed = 0;
        int checkpointed = 0;
        boolean checkpointsRefused = false;
        for (Unsealed record : store.unsealed()) {
            if (record.entries() == 0) {
                continue;
            }
            EvidenceRecord.Seal seal = new EvidenceRecord.Seal(now, key.sign(record.latestHash()), key.keyId(), record.entries(),
                    record.latestHash());
            if (record.closed() && !record.platform()) {
                store.seal(record.jobId(), seal);
                events.publish("audit.sealed", "audit", record.jobId(), actor,
                        mapper.createObjectNode().put("jobId", record.jobId()).put("keyId", key.keyId()));
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
        return new SealResult(now, key.sign(ledger.toString()), key.keyId(), sealed, checkpointed);
    }

    @Override
    public Mono<ShareLink> share(String jobId) {
        return CurrentIdentity.current().flatMap(who -> Mono.fromCallable(() -> {
            EvidenceRecord record = store.record(jobId).orElseThrow(() -> new NotFoundException("evidence record", jobId));
            Set<String> teams = owners.teams(jobId);
            if (!canShare(who, record, teams)) {
                throw new ForbiddenException(who.name() + " may not share the evidence of " + jobId + ": only its requester ("
                        + record.requester() + ")" + (teams.isEmpty() ? "" : ", a member of " + String.join(", ", teams)) + " or an admin may");
            }
            ShareToken token = store.shareToken(jobId, clock.instant().plus(SHARE_FOR));
            ObjectNode data = mapper.createObjectNode()
                    .put("recordId", jobId)
                    .put("by", who.id())
                    .put("expiresAt", token.expiresAt().toString());
            events.publish("audit.shared", "audit", jobId, who.id(), data);
            return new ShareLink("/api/v1/audit/shared/" + token.token(), token.expiresAt());
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** The requester, a member of the job's teams, or an admin. */
    static boolean canShare(Identity who, EvidenceRecord record, Set<String> teams) {
        if (who.admin() || (who.id() != null && who.id().equals(record.requester()))) {
            return true;
        }
        return who.teams() != null && who.teams().stream().anyMatch(teams::contains);
    }
}

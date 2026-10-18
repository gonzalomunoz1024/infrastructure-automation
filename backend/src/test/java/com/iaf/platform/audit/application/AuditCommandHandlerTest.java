package com.iaf.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.audit.adapters.outbound.InMemoryEvidenceStore;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.ports.inbound.AuditCommands.SealResult;
import com.iaf.platform.audit.ports.inbound.AuditCommands.ShareLink;
import com.iaf.platform.audit.ports.outbound.RecordOwners;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.application.CurrentIdentity;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditCommandHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final ObjectMapper mapper = new ObjectMapper();
    final InMemoryEvidenceStore store = new InMemoryEvidenceStore();
    final EventBus events = new EventBus(clock);
    final List<DomainEvent> published = new ArrayList<>();
    final AuditSigner signer = new AuditSigner(withKey("test-signing-key"));
    /** job_closed ran vm-restart for ana, owned by team-compute; nothing else is known to the runs index. */
    final RecordOwners owners = jobId -> "job_closed".equals(jobId) ? Set.of("team-compute") : Set.of();
    final AuditCommandHandler handler = new AuditCommandHandler(store, signer, events, clock, mapper, owners);

    static final Identity ANA = new Identity("ana", "Ana", null, List.of("team-data"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity MATEO = new Identity("mateo", "Mateo", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity DEV = new Identity("dev", "Dev", null, List.of("team-web"), List.of("developer"), false, Identity.Kind.USER);

    static <T> Mono<T> as(Identity who, Mono<T> mono) {
        return mono.contextWrite(ctx -> CurrentIdentity.with(ctx, who));
    }

    @BeforeEach
    void records() {
        events.register(published::add);
        store.append("job_closed", EvidenceEntry.draft(NOW.minusSeconds(120), EvidenceKind.REQUESTED, "ana",
                mapper.createObjectNode().put("workflow", "vm-restart").put("version", "1.2.0").put("channel", "catalog")));
        store.append("job_closed", EvidenceEntry.draft(NOW.minusSeconds(60), EvidenceKind.CLOSED, "platform", mapper.createObjectNode().put("status", "succeeded")));
        store.append("job_open", EvidenceEntry.draft(NOW.minusSeconds(30), EvidenceKind.REQUESTED, "mateo",
                mapper.createObjectNode().put("workflow", "create-vm").put("version", "2.0.1").put("channel", "rest")));
    }

    @Test
    void sealsClosedRecordsAndCheckpointsOpenOnesInOneSignedRun() {
        StepVerifier.create(handler.seal())
                .assertNext(result -> {
                    assertThat(result.sealedAt()).isEqualTo(NOW);
                    assertThat(result.sealed()).isEqualTo(1);
                    assertThat(result.checkpointed()).isEqualTo(1);
                    assertThat(result.keyId()).isEqualTo(signer.keyId());
                    String ledger = "job_open " + store.record("job_open").orElseThrow().latestHash() + "\n"
                            + "job_closed " + store.record("job_closed").orElseThrow().latestHash() + "\n";
                    assertThat(signer.verify(ledger, result.signature())).isTrue();
                })
                .verifyComplete();

        EvidenceRecord sealed = store.record("job_closed").orElseThrow();
        assertThat(sealed.sealed()).isNotNull();
        assertThat(sealed.sealed().at()).isEqualTo(NOW);
        assertThat(sealed.sealed().keyId()).isEqualTo(signer.keyId());
        assertThat(sealed.sealed().seq()).isEqualTo(2);
        assertThat(sealed.sealed().hash()).isEqualTo(sealed.latestHash());
        assertThat(signer.verify(sealed.latestHash(), sealed.sealed().signature())).isTrue();

        EvidenceRecord open = store.record("job_open").orElseThrow();
        assertThat(open.sealed()).isNull();
        assertThat(open.checkpoints()).hasSize(1);
        assertThat(open.latestCheckpoint().seq()).isEqualTo(1);
        assertThat(signer.verify(open.latestHash(), open.latestCheckpoint().signature())).isTrue();

        assertThat(published).hasSize(1);
        DomainEvent event = published.get(0);
        assertThat(event.type()).isEqualTo("audit.sealed");
        assertThat(event.aggregateId()).isEqualTo("job_closed");
        assertThat(event.data().get("jobId").asText()).isEqualTo("job_closed");
        assertThat(event.data().get("keyId").asText()).isEqualTo(signer.keyId());
    }

    @Test
    void aSecondRunSealsNothingNew() {
        handler.sealAll("platform");
        String firstSignature = store.record("job_closed").orElseThrow().sealed().signature();

        SealResult again = handler.sealAll("platform");

        assertThat(again.sealed()).isZero();
        assertThat(again.checkpointed()).isZero();
        assertThat(store.record("job_closed").orElseThrow().sealed().signature()).isEqualTo(firstSignature);
        assertThat(store.record("job_open").orElseThrow().checkpoints()).hasSize(1);
        assertThat(published).hasSize(1);
    }

    @Test
    void aCheckpointedRecordKeepsAcceptingEntriesAndIsCheckpointedAgainWhenItsHeadMoves() {
        handler.sealAll("platform");
        store.append("job_open", EvidenceEntry.draft(NOW, EvidenceKind.STEP, "platform", mapper.createObjectNode().put("step", "s").put("status", "succeeded")));

        SealResult next = handler.sealAll("platform");

        EvidenceRecord open = store.record("job_open").orElseThrow();
        assertThat(open.entries()).hasSize(2);
        assertThat(next.checkpointed()).isEqualTo(1);
        assertThat(open.checkpoints()).extracting(EvidenceRecord.Seal::seq).containsExactly(1, 2);
        assertThat(open.latestCheckpoint().hash()).isEqualTo(open.latestHash());
    }

    @Test
    void thePlatformStreamIsCheckpointedNeverSealed() {
        store.append(EvidenceRecord.PLATFORM, EvidenceEntry.draft(NOW, EvidenceKind.DECISION, "ana",
                mapper.createObjectNode().put("event", "workflow.draft-saved")));
        store.append(EvidenceRecord.PLATFORM, EvidenceEntry.draft(NOW, EvidenceKind.CLOSED, "ana", mapper.createObjectNode()));

        handler.sealAll("platform");

        EvidenceRecord stream = store.record(EvidenceRecord.PLATFORM).orElseThrow();
        assertThat(stream.sealed()).isNull();
        assertThat(stream.checkpoints()).hasSize(1);
    }

    @Test
    void aStoreWithoutCheckpointsStillGetsItsClosedRecordsSealed() {
        InMemoryEvidenceStore plain = new InMemoryEvidenceStore() {
            @Override
            public EvidenceRecord checkpoint(String jobId, EvidenceRecord.Seal checkpoint) {
                throw new UnsupportedOperationException("no checkpoints here");
            }
        };
        plain.append("job_a", EvidenceEntry.draft(NOW, EvidenceKind.REQUESTED, "ana", mapper.createObjectNode().put("workflow", "w")));
        plain.append("job_a", EvidenceEntry.draft(NOW, EvidenceKind.CLOSED, "platform", mapper.createObjectNode().put("status", "succeeded")));
        plain.append("job_b", EvidenceEntry.draft(NOW, EvidenceKind.REQUESTED, "ana", mapper.createObjectNode().put("workflow", "w")));

        SealResult result = new AuditCommandHandler(plain, signer, events, clock, mapper, owners).sealAll("platform");

        assertThat(result.sealed()).isEqualTo(1);
        assertThat(result.checkpointed()).isZero();
        assertThat(plain.record("job_a").orElseThrow().sealed()).isNotNull();
    }

    @Test
    void aSealingRunReadsOnlyTheRecordsStillTakingSignatures() {
        InMemoryEvidenceStore guarded = new InMemoryEvidenceStore() {
            @Override
            public List<EvidenceRecord> records(com.iaf.platform.audit.domain.EvidenceFilter filter) {
                throw new AssertionError("a sealing run lists no record with its entries");
            }
        };
        guarded.append("job_a", EvidenceEntry.draft(NOW, EvidenceKind.REQUESTED, "ana", mapper.createObjectNode().put("workflow", "w")));
        guarded.append("job_a", EvidenceEntry.draft(NOW, EvidenceKind.CLOSED, "platform", mapper.createObjectNode().put("status", "succeeded")));

        SealResult result = new AuditCommandHandler(guarded, signer, events, clock, mapper, owners).sealAll("platform");

        assertThat(result.sealed()).isEqualTo(1);
        assertThat(guarded.unsealed()).isEmpty();
    }

    @Test
    void theScheduledRunUsesTheSameLogic() {
        handler.sealOnSchedule();

        assertThat(store.record("job_closed").orElseThrow().sealed()).isNotNull();
        assertThat(published.get(0).actor()).isEqualTo("platform");
    }

    @Test
    void sharesARecordForSevenDays() {
        StepVerifier.create(handler.share("job_closed"))
                .assertNext(link -> {
                    assertThat(link.url()).startsWith("/api/v1/audit/shared/");
                    assertThat(link.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
                    String token = link.url().substring("/api/v1/audit/shared/".length());
                    assertThat(token).hasSizeGreaterThanOrEqualTo(32);
                    assertThat(store.findShareToken(token)).isPresent();
                    assertThat(store.findShareToken(token).orElseThrow().jobId()).isEqualTo("job_closed");
                    // the link is on the record: who shared what until when, never the token
                    DomainEvent shared = published.get(published.size() - 1);
                    assertThat(shared.type()).isEqualTo("audit.shared");
                    assertThat(shared.aggregate()).isEqualTo("audit");
                    assertThat(shared.aggregateId()).isEqualTo("job_closed");
                    assertThat(shared.actor()).isEqualTo("platform");
                    assertThat(shared.data().get("recordId").asText()).isEqualTo("job_closed");
                    assertThat(shared.data().get("by").asText()).isEqualTo("platform");
                    assertThat(shared.data().get("expiresAt").asText()).isEqualTo(NOW.plus(Duration.ofDays(7)).toString());
                    assertThat(shared.data().toString()).doesNotContain(token);
                })
                .verifyComplete();
    }

    @Test
    void aRecordIsSharedByItsRequesterItsTeamsOrAnAdminAndNobodyElse() {
        StepVerifier.create(as(ANA, handler.share("job_closed"))).expectNextCount(1).verifyComplete();
        StepVerifier.create(as(MATEO, handler.share("job_closed"))).expectNextCount(1).verifyComplete();
        StepVerifier.create(as(DEV, handler.share("job_closed")))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class)
                        .hasMessage("Dev may not share the evidence of job_closed: only its requester (ana), a member of team-compute or an admin may"))
                .verify();
        // a record the runs index does not know: its requester or an admin
        StepVerifier.create(as(DEV, handler.share("job_open"))).expectError(ForbiddenException.class).verify();
        ShareLink byAdmin = as(Identity.SYSTEM, handler.share("job_open")).block();
        assertThat(byAdmin).isNotNull();
        assertThat(published).extracting(DomainEvent::type).containsExactly("audit.shared", "audit.shared", "audit.shared");
        assertThat(published).extracting(e -> e.data().get("by").asText()).containsExactly("ana", "mateo", "platform");
    }

    @Test
    void onlyAdminsSealOnDemand() {
        assertThatThrownBy(() -> as(MATEO, handler.seal()).block())
                .isInstanceOf(ForbiddenException.class)
                .hasMessageStartingWith("Mateo may not seal evidence: only admins seal on demand");
        assertThat(store.record("job_closed").orElseThrow().sealed()).isNull();
        assertThat(published).isEmpty();
        StepVerifier.create(as(Identity.SYSTEM, handler.seal())).assertNext(r -> assertThat(r.sealed()).isEqualTo(1)).verifyComplete();
    }

    @Test
    void sharingAnUnknownRecordIsNotFound() {
        StepVerifier.create(handler.share("job_missing")).expectError(NotFoundException.class).verify();
    }

    @Test
    void aBlankSigningKeyMeansARandomKeyPerRun() {
        AuditSigner one = new AuditSigner(withKey(""));
        AuditSigner two = new AuditSigner(withKey(""));
        AuditSigner configured = new AuditSigner(withKey("test-signing-key"));

        assertThat(one.keyId()).startsWith("k").hasSize(13);
        assertThat(one.keyId()).isNotEqualTo(two.keyId());
        assertThat(one.verify("payload", two.sign("payload"))).isFalse();
        assertThat(configured.keyId()).isEqualTo(signer.keyId());
        assertThat(configured.sign("payload")).isEqualTo(signer.sign("payload"));
    }

    static PlatformProperties withKey(String key) {
        PlatformProperties props = new PlatformProperties();
        props.getAudit().setSigningKey(key);
        return props;
    }
}

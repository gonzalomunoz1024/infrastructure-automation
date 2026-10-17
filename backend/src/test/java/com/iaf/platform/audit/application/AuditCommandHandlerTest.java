package com.iaf.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.audit.adapters.outbound.InMemoryEvidenceStore;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.ports.inbound.AuditCommands.SealResult;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.config.PlatformProperties;

import reactor.test.StepVerifier;

class AuditCommandHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final ObjectMapper mapper = new ObjectMapper();
    final InMemoryEvidenceStore store = new InMemoryEvidenceStore();
    final EventBus events = new EventBus(clock);
    final List<DomainEvent> published = new ArrayList<>();
    final AuditSigner signer = new AuditSigner(withKey("test-signing-key"));
    final AuditCommandHandler handler = new AuditCommandHandler(store, signer, events, clock, mapper);

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
    void sealsEveryClosedUnsealedRecordAndPublishesIt() {
        StepVerifier.create(handler.seal())
                .assertNext(result -> {
                    assertThat(result.sealedAt()).isEqualTo(NOW);
                    assertThat(result.sealed()).isEqualTo(1);
                    assertThat(result.keyId()).isEqualTo(signer.keyId());
                    String ledger = "job_closed " + store.record("job_closed").orElseThrow().latestHash() + "\n";
                    assertThat(signer.verify(ledger, result.signature())).isTrue();
                })
                .verifyComplete();

        EvidenceRecord sealed = store.record("job_closed").orElseThrow();
        assertThat(sealed.sealed()).isNotNull();
        assertThat(sealed.sealed().at()).isEqualTo(NOW);
        assertThat(sealed.sealed().keyId()).isEqualTo(signer.keyId());
        assertThat(signer.verify(sealed.latestHash(), sealed.sealed().signature())).isTrue();
        assertThat(store.record("job_open").orElseThrow().sealed()).isNull();

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
        assertThat(store.record("job_closed").orElseThrow().sealed().signature()).isEqualTo(firstSignature);
        assertThat(published).hasSize(1);
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
                })
                .verifyComplete();
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

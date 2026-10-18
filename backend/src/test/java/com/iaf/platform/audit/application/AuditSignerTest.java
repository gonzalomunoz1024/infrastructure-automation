package com.iaf.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.audit.adapters.outbound.InMemoryEvidenceStore;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.common.adapters.credentials.InMemoryCredentials;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.config.PlatformProperties;

class AuditSignerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final String REFERENCE = "vault:audit/signing-key";

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final InMemoryCredentials credentials = new InMemoryCredentials(new PlatformProperties(), clock);

    void storeKey(String value) {
        credentials.store(REFERENCE, "token", value, "test", null).block();
    }

    @Test
    void aVaultReferenceIsResolvedThroughTheCredentialsStoreWhenAKeyIsNeeded() {
        AuditSigner fromVault = new AuditSigner(AuditCommandHandlerTest.withKey(REFERENCE), credentials);
        storeKey("test-signing-key");
        AuditSigner literal = new AuditSigner(AuditCommandHandlerTest.withKey("test-signing-key"));

        assertThat(fromVault.keyId()).isEqualTo(literal.keyId());
        assertThat(fromVault.sign("payload")).isEqualTo(literal.sign("payload"));
        assertThat(fromVault.verify("payload", literal.sign("payload"))).isTrue();
        assertThat(fromVault.key()).as("the same value gives the same key").isSameAs(fromVault.key());
        assertThat(fromVault.key().toString()).doesNotContain("test-signing-key");
    }

    @Test
    void aRotatedKeyIsPickedUpWithoutARestart() {
        AuditSigner signer = new AuditSigner(AuditCommandHandlerTest.withKey(REFERENCE), credentials);
        storeKey("test-signing-key");
        AuditSigner.Key before = signer.key();
        String signed = before.sign("payload");

        storeKey("test-signing-key-rotated");

        assertThat(signer.keyId()).isNotEqualTo(before.keyId());
        assertThat(signer.verify("payload", signed)).isFalse();
        assertThat(before.verify("payload", signed)).as("a key taken before keeps verifying what it signed").isTrue();
    }

    @Test
    void aReferenceThatResolvesToNothingFailsNamingTheReference() {
        AuditSigner signer = new AuditSigner(AuditCommandHandlerTest.withKey("vault:audit/missing"), credentials);

        assertThatThrownBy(signer::key).isInstanceOf(IllegalStateException.class)
                .hasMessage("platform.audit.signing-key vault:audit/missing resolves to nothing: store the signing key under that name");
    }

    @Test
    void aReferenceNeedsACredentialsStore() {
        assertThatThrownBy(() -> new AuditSigner(AuditCommandHandlerTest.withKey(REFERENCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform.audit.signing-key is a vault: reference");
    }

    @Test
    void sealsSignedWithTheVaultKeyVerifyUntilTheKeyRotates() {
        ObjectMapper mapper = new ObjectMapper();
        InMemoryEvidenceStore store = new InMemoryEvidenceStore();
        store.append("job_closed", EvidenceEntry.draft(NOW.minusSeconds(120), EvidenceKind.REQUESTED, "ana",
                mapper.createObjectNode().put("workflow", "vm-restart").put("version", "1.2.0").put("channel", "catalog")));
        store.append("job_closed", EvidenceEntry.draft(NOW.minusSeconds(60), EvidenceKind.CLOSED, "platform", mapper.createObjectNode().put("status", "succeeded")));
        storeKey("test-signing-key");
        AuditSigner signer = new AuditSigner(AuditCommandHandlerTest.withKey(REFERENCE), credentials);
        AuditCommandHandler commands = new AuditCommandHandler(store, signer, new EventBus(clock), clock, mapper, jobId -> Set.of());
        AuditQueryHandler queries = new AuditQueryHandler(store, signer, clock, mapper);

        assertThat(commands.sealAll("platform").sealed()).isEqualTo(1);
        EvidenceRecord sealed = store.record("job_closed").orElseThrow();
        assertThat(sealed.sealed().keyId()).isEqualTo(signer.keyId());
        assertThat(queries.verification(sealed).seal()).isEqualTo("verified");

        storeKey("test-signing-key-rotated");
        assertThat(queries.verification(sealed).seal()).as("signed with a key this instance no longer has").isEqualTo("unverifiable");
    }
}

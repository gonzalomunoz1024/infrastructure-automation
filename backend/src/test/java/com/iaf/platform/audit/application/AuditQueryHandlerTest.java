package com.iaf.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iaf.platform.audit.adapters.outbound.FileSystemEvidenceStore;
import com.iaf.platform.audit.adapters.outbound.InMemoryEvidenceStore;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.domain.Signatures;
import com.iaf.platform.audit.ports.inbound.AuditQueries;
import com.iaf.platform.audit.ports.inbound.AuditQueries.AuditRow;
import com.iaf.platform.audit.ports.inbound.AuditQueries.FilterSpec;
import com.iaf.platform.audit.ports.inbound.AuditQueries.SpooledFile;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.config.PlatformProperties;

import reactor.test.StepVerifier;

class AuditQueryHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    /** What the Spring mapper does: ISO dates, non_null. */
    static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final InMemoryEvidenceStore store = new InMemoryEvidenceStore();
    final AuditSigner signer = new AuditSigner(AuditCommandHandlerTest.withKey("test-signing-key"));
    @TempDir
    Path spool;
    AuditQueryHandler handler;

    @Test
    void everyStoreReadRunsOnBoundedElasticNeverOnTheCallersThread(@TempDir Path dir) {
        Set<String> threads = new CopyOnWriteArraySet<>();
        FileSystemEvidenceStore files = new FileSystemEvidenceStore(dir, 7) {
            @Override
            public Optional<EvidenceRecord> record(String jobId) {
                threads.add(Thread.currentThread().getName());
                return super.record(jobId);
            }

            @Override
            public List<EvidenceRecord> records(EvidenceFilter filter) {
                threads.add(Thread.currentThread().getName());
                return super.records(filter);
            }

            @Override
            public Optional<ShareToken> findShareToken(String token) {
                threads.add(Thread.currentThread().getName());
                return super.findShareToken(token);
            }
        };
        files.append("job_f", EvidenceEntry.draft(NOW, EvidenceKind.REQUESTED, "ana", JSON.createObjectNode().put("workflow", "vm-restart")));
        files.append(EvidenceRecord.PLATFORM, EvidenceEntry.draft(NOW, EvidenceKind.DECISION, "ana", JSON.createObjectNode().put("event", "workflow.published")));
        ShareToken link = files.shareToken("job_f", NOW.plus(Duration.ofHours(1)));
        AuditQueryHandler h = new AuditQueryHandler(files, signer, clock, JSON, dir.resolve("spool"));
        threads.clear();

        StepVerifier.create(h.list(null, null, null, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(h.record("job_f")).expectNextCount(1).verifyComplete();
        StepVerifier.create(h.verify("job_f")).expectNextCount(1).verifyComplete();
        StepVerifier.create(h.platform(null, null, null, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(h.export("job_f", "pdf")).expectNextCount(1).verifyComplete();
        StepVerifier.create(h.exportPack(null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(h.shared(link.token())).expectNextCount(1).verifyComplete();

        assertThat(threads).isNotEmpty().allMatch(t -> t.startsWith("boundedElastic"), "every store read is on boundedElastic");
    }

    @BeforeEach
    void records() {
        handler = new AuditQueryHandler(store, signer, clock, JSON, spool);
        // yesterday: a closed vm-restart job by ana
        store.append("job_old", EvidenceEntry.draft(NOW.minus(Duration.ofDays(1)), EvidenceKind.REQUESTED, "ana",
                JSON.createObjectNode().put("workflow", "vm-restart").put("version", "1.2.0").put("channel", "catalog")));
        store.append("job_old", EvidenceEntry.draft(NOW.minus(Duration.ofDays(1)).plusSeconds(90), EvidenceKind.CLOSED, "platform",
                JSON.createObjectNode().put("status", "failed").put("errorClass", "Timeout")));
        // just now: a running create-vm job by mateo with a change link
        store.append("job_new", EvidenceEntry.draft(NOW.minusSeconds(30), EvidenceKind.REQUESTED, "mateo",
                JSON.createObjectNode().put("workflow", "create-vm").put("version", "2.0.1").put("channel", "rest")));
        store.append("job_new", EvidenceEntry.draft(NOW.minusSeconds(20), EvidenceKind.CHANGE, "platform",
                JSON.createObjectNode().put("id", "CHG0001").put("url", "https://snow.example/CHG0001").put("state", "open")));
        // a release decision on a pseudo job
        store.append("release:rel_1", EvidenceEntry.draft(NOW.minusSeconds(10), EvidenceKind.DECISION, "platform",
                JSON.createObjectNode().put("event", "release.promoted").put("workflow", "create-vm").put("from", "1.9.0").put("to", "2.0.1")));
    }

    @Test
    void listsRowsNewestFirstAndFilters() {
        StepVerifier.create(handler.list(null, null, null, null).map(AuditRow::jobId))
                .expectNext("release:rel_1", "job_new", "job_old")
                .verifyComplete();

        StepVerifier.create(handler.list("vm-restart", null, null, null))
                .assertNext(row -> {
                    assertThat(row.jobId()).isEqualTo("job_old");
                    assertThat(row.requester()).isEqualTo("ana");
                    assertThat(row.channel()).isEqualTo("catalog");
                    assertThat(row.at()).isEqualTo(NOW.minus(Duration.ofDays(1)));
                    assertThat(row.entries()).isEqualTo(2);
                    assertThat(row.status()).isEqualTo("failed");
                })
                .verifyComplete();

        StepVerifier.create(handler.list(null, null, null, "mateo").map(AuditRow::status)).expectNext("running").verifyComplete();
        StepVerifier.create(handler.list(null, "2026-10-17", null, null).map(AuditRow::jobId)).expectNext("release:rel_1", "job_new").verifyComplete();
        StepVerifier.create(handler.list(null, "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z", null).map(AuditRow::jobId)).expectNext("job_old").verifyComplete();
        StepVerifier.create(handler.list(null, "2026-10-16", "2026-10-16", null).map(AuditRow::jobId))
                .as("a date given as 'to' covers that whole day").expectNext("job_old").verifyComplete();
        StepVerifier.create(handler.list("create-vm", null, null, null).map(AuditRow::status)).expectNext("promoted", "running").verifyComplete();
    }

    @Test
    void aBadDateIsABadRequest() {
        StepVerifier.create(handler.list(null, "yesterday", null, null)).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void servesOneRecordAnd404ForUnknownIds() {
        StepVerifier.create(handler.record("job_new"))
                .assertNext(r -> {
                    assertThat(r.workflow()).isEqualTo("create-vm");
                    assertThat(r.change()).isEqualTo(new EvidenceRecord.Change("CHG0001", "https://snow.example/CHG0001"));
                })
                .verifyComplete();
        StepVerifier.create(handler.record("job_missing")).expectError(NotFoundException.class).verify();
    }

    @Test
    void verifiesAnIntactChainAndItsSeal() {
        StepVerifier.create(handler.verify("job_old"))
                .assertNext(v -> {
                    assertThat(v.valid()).isTrue();
                    assertThat(v.entries()).isEqualTo(2);
                    assertThat(v.brokenAt()).isNull();
                    assertThat(v.seal()).isNull();
                })
                .verifyComplete();

        EvidenceRecord record = store.record("job_old").orElseThrow();
        store.seal("job_old", new EvidenceRecord.Seal(NOW, signer.sign(record.latestHash()), signer.keyId()));
        StepVerifier.create(handler.verify("job_old")).assertNext(v -> {
            assertThat(v.valid()).isTrue();
            assertThat(v.seal()).isEqualTo("verified");
        }).verifyComplete();
    }

    @Test
    void aSealByAnotherKeyIsUnverifiableAndAWrongSignatureInvalidates() {
        EvidenceStore tampered = mock(EvidenceStore.class);
        EvidenceRecord record = store.record("job_old").orElseThrow();
        when(tampered.record("other-key")).thenReturn(Optional.of(record.withSeal(new EvidenceRecord.Seal(NOW, "c2ln", "kdeadbeef0000"))));
        when(tampered.record("wrong-sig")).thenReturn(Optional.of(record.withSeal(new EvidenceRecord.Seal(NOW, "c2ln", signer.keyId()))));
        AuditQueryHandler h = new AuditQueryHandler(tampered, signer, clock, JSON);

        StepVerifier.create(h.verify("other-key")).assertNext(v -> {
            assertThat(v.valid()).isTrue();
            assertThat(v.seal()).isEqualTo("unverifiable");
        }).verifyComplete();
        StepVerifier.create(h.verify("wrong-sig")).assertNext(v -> {
            assertThat(v.valid()).isFalse();
            assertThat(v.seal()).isEqualTo("mismatch");
            assertThat(v.brokenAt()).isNull();
        }).verifyComplete();
    }

    @Test
    void verifyFindsATamperedEntry() {
        EvidenceRecord record = store.record("job_old").orElseThrow();
        List<EvidenceEntry> entries = new ArrayList<>(record.entries());
        EvidenceEntry closed = entries.get(1);
        entries.set(1, new EvidenceEntry(closed.seq(), closed.at(), closed.kind(), closed.actor(),
                JSON.createObjectNode().put("status", "succeeded"), closed.hash(), closed.previous()));
        EvidenceRecord forged = new EvidenceRecord(record.jobId(), record.workflow(), record.version(), record.requester(), record.channel(),
                entries, record.latestHash(), null, record.retention(), null);
        EvidenceStore tampered = mock(EvidenceStore.class);
        when(tampered.record("job_old")).thenReturn(Optional.of(forged));

        StepVerifier.create(new AuditQueryHandler(tampered, signer, clock, JSON).verify("job_old"))
                .assertNext(v -> {
                    assertThat(v.valid()).isFalse();
                    assertThat(v.brokenAt()).isEqualTo(2);
                    assertThat(v.entries()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    void exportsJsonAndPdf() throws IOException {
        StepVerifier.create(handler.export("job_old", "json"))
                .assertNext(file -> {
                    assertThat(file.filename()).isEqualTo("evidence-job_old.json");
                    assertThat(file.contentType()).isEqualTo("application/json");
                    try {
                        EvidenceRecord back = JSON.readValue(file.bytes(), EvidenceRecord.class);
                        assertThat(back.jobId()).isEqualTo("job_old");
                        assertThat(back.entries()).hasSize(2);
                        assertThat(back.latestHash()).isEqualTo(store.record("job_old").orElseThrow().latestHash());
                        assertThat(HashChain.verify(back.entries())).isEqualTo(-1);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                })
                .verifyComplete();

        StepVerifier.create(handler.export("release:rel_1", "pdf"))
                .assertNext(file -> {
                    assertThat(file.filename()).isEqualTo("evidence-release-rel_1.pdf");
                    assertThat(file.contentType()).isEqualTo("application/pdf");
                    assertThat(new String(file.bytes(), StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.4").contains("(Evidence record release:rel_1) Tj");
                })
                .verifyComplete();

        StepVerifier.create(handler.export("job_old", "xml")).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.export("job_missing", "pdf")).expectError(NotFoundException.class).verify();
    }

    @Test
    void anExportPackZipsEveryMatchingRecordWithASignedManifest() throws IOException {
        var file = handler.exportPack(new FilterSpec(null, "2026-10-17", null, null)).block();

        assertThat(file.filename()).startsWith("evidence-pack-").endsWith(".zip");
        assertThat(file.contentType()).isEqualTo("application/zip");
        assertThat(file.path().getParent()).isEqualTo(spool);
        assertThat(file.size()).isEqualTo(Files.size(file.path()));
        Map<String, byte[]> entries = unzip(file);
        assertThat(entries.keySet()).containsExactly("records/release-rel_1.json", "records/job_new.json", "manifest.json");

        EvidenceRecord fromZip = JSON.readValue(entries.get("records/job_new.json"), EvidenceRecord.class);
        assertThat(fromZip.jobId()).isEqualTo("job_new");

        JsonNode manifest = JSON.readTree(entries.get("manifest.json"));
        assertThat(manifest.get("generatedAt").asText()).isEqualTo("2026-10-17T10:00:00Z");
        assertThat(manifest.get("keyId").asText()).isEqualTo(signer.keyId());
        assertThat(manifest.get("filter").get("from").asText()).isEqualTo("2026-10-17T00:00:00Z");
        JsonNode files = manifest.get("files");
        assertThat(files).hasSize(2);
        for (JsonNode f : files) {
            byte[] content = entries.get(f.get("name").asText());
            assertThat(content).as(f.get("name").asText()).isNotNull();
            assertThat(f.get("sha256").asText()).isEqualTo(Signatures.sha256Hex(content));
            assertThat(f.get("latestHash").asText()).isEqualTo(store.record(f.get("jobId").asText()).orElseThrow().latestHash());
            assertThat(f.get("sealed").asBoolean()).isFalse();
        }
        assertThat(signer.verify(HashChain.canonical(files), manifest.get("signature").asText())).isTrue();
        assertThat(signer.verify(HashChain.canonical(files) + "x", manifest.get("signature").asText())).isFalse();
    }

    @Test
    void anEmptyPackStillCarriesASignedManifest() throws IOException {
        var file = handler.exportPack(new FilterSpec("nothing", null, null, null)).block();

        Map<String, byte[]> entries = unzip(file);
        assertThat(entries.keySet()).containsExactly("manifest.json");
        JsonNode manifest = JSON.readTree(entries.get("manifest.json"));
        assertThat(manifest.get("files")).isEmpty();
        assertThat(signer.verify("[]", manifest.get("signature").asText())).isTrue();
    }

    @Test
    void aSharedLinkServesTheRecordUntilItExpires() {
        ShareToken live = store.shareToken("job_new", NOW.plus(Duration.ofHours(1)));
        ShareToken expired = store.shareToken("job_new", NOW.minusSeconds(1));

        StepVerifier.create(handler.shared(live.token())).assertNext(r -> assertThat(r.jobId()).isEqualTo("job_new")).verifyComplete();
        StepVerifier.create(handler.shared(expired.token()))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.GONE);
                })
                .verify();
        StepVerifier.create(handler.shared("nope")).expectError(NotFoundException.class).verify();
    }

    @Test
    void verificationReportsEveryCheckpointAndTheFinalSeal() {
        EvidenceRecord open = store.record("job_new").orElseThrow();
        EvidenceEntry first = open.entries().get(0);
        store.checkpoint("job_new", new EvidenceRecord.Seal(NOW, signer.sign(first.hash()), signer.keyId(), 1, first.hash()));
        store.checkpoint("job_new", new EvidenceRecord.Seal(NOW.plusSeconds(3600), signer.sign(open.latestHash()), signer.keyId(), 2, open.latestHash()));
        store.append("job_new", EvidenceEntry.draft(NOW, EvidenceKind.CLOSED, "platform", JSON.createObjectNode().put("status", "succeeded")));
        EvidenceRecord closed = store.record("job_new").orElseThrow();
        store.seal("job_new", new EvidenceRecord.Seal(NOW.plusSeconds(7200), signer.sign(closed.latestHash()), signer.keyId(), 3, closed.latestHash()));

        StepVerifier.create(handler.verify("job_new"))
                .assertNext(v -> {
                    assertThat(v.valid()).isTrue();
                    assertThat(v.seal()).isEqualTo("verified");
                    assertThat(v.seals()).extracting(AuditQueries.SealCheck::kind).containsExactly("checkpoint", "checkpoint", "seal");
                    assertThat(v.seals()).extracting(AuditQueries.SealCheck::seq).containsExactly(1, 2, 3);
                    assertThat(v.seals()).extracting(AuditQueries.SealCheck::status).containsOnly("verified");
                })
                .verifyComplete();
    }

    @Test
    void aCheckpointThatNoLongerMatchesItsEntryMakesTheRecordInvalid() {
        EvidenceRecord open = store.record("job_new").orElseThrow();
        EvidenceStore tampered = mock(EvidenceStore.class);
        EvidenceRecord withBadCheckpoint = open
                .withCheckpoint(new EvidenceRecord.Seal(NOW, signer.sign("not-the-hash"), signer.keyId(), 1, "not-the-hash"))
                .withCheckpoint(new EvidenceRecord.Seal(NOW, "c2ln", "kother", 2, open.latestHash()));
        when(tampered.record("job_new")).thenReturn(Optional.of(withBadCheckpoint));

        StepVerifier.create(new AuditQueryHandler(tampered, signer, clock, JSON).verify("job_new"))
                .assertNext(v -> {
                    assertThat(v.valid()).isFalse();
                    assertThat(v.brokenAt()).isNull();
                    assertThat(v.seal()).isNull();
                    assertThat(v.seals()).extracting(AuditQueries.SealCheck::status).containsExactly("mismatch", "unverifiable");
                })
                .verifyComplete();
    }

    @Test
    void thePlatformStreamIsFilteredButVerifiedWhole() {
        store.append(EvidenceRecord.PLATFORM, EvidenceEntry.draft(NOW.minus(Duration.ofDays(1)), EvidenceKind.DECISION, "ana",
                JSON.createObjectNode().put("event", "workflow.draft-saved").put("aggregate", "workflow").put("aggregateId", "vm-restart")));
        store.append(EvidenceRecord.PLATFORM, EvidenceEntry.draft(NOW.minusSeconds(60), EvidenceKind.DECISION, "mateo",
                JSON.createObjectNode().put("event", "credential.stored").put("aggregate", "credential").put("aggregateId", "vsphere/token")));
        store.append(EvidenceRecord.PLATFORM, EvidenceEntry.draft(NOW.minusSeconds(30), EvidenceKind.DECISION, "ana",
                JSON.createObjectNode().put("event", "workflow.published").put("aggregate", "workflow").put("aggregateId", "vm-restart")));

        StepVerifier.create(handler.platform(null, null, null, null))
                .assertNext(p -> {
                    assertThat(p.entries()).hasSize(3);
                    assertThat(p.total()).isEqualTo(3);
                    assertThat(p.latestHash()).isEqualTo(store.record(EvidenceRecord.PLATFORM).orElseThrow().latestHash());
                    assertThat(p.verification().valid()).isTrue();
                    assertThat(p.verification().entries()).isEqualTo(3);
                })
                .verifyComplete();
        StepVerifier.create(handler.platform("2026-10-17", null, "workflow", "ana"))
                .assertNext(p -> {
                    assertThat(p.entries()).extracting(e -> e.data().get("event").asText()).containsExactly("workflow.published");
                    assertThat(p.total()).isEqualTo(3);
                })
                .verifyComplete();
        StepVerifier.create(handler.platform(null, null, "credential:vsphere/token", null).map(p -> p.entries().size())).expectNext(1).verifyComplete();
        StepVerifier.create(handler.platform(null, null, "vm-restart", null).map(p -> p.entries().size())).expectNext(2).verifyComplete();
        StepVerifier.create(handler.list(null, null, null, null).map(AuditRow::jobId))
                .as("the platform stream is not a job row")
                .expectNext("release:rel_1", "job_new", "job_old")
                .verifyComplete();
    }

    @Test
    void anEmptyPlatformStreamIsValid() {
        StepVerifier.create(handler.platform(null, null, null, null))
                .assertNext(p -> {
                    assertThat(p.entries()).isEmpty();
                    assertThat(p.latestHash()).isEqualTo(HashChain.GENESIS);
                    assertThat(p.verification().valid()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void anExportPackCarriesTheWholePlatformStream() throws IOException {
        store.append(EvidenceRecord.PLATFORM, EvidenceEntry.draft(NOW.minus(Duration.ofDays(3)), EvidenceKind.DECISION, "ana",
                JSON.createObjectNode().put("event", "workflow.draft-saved")));

        var file = handler.exportPack(new FilterSpec("vm-restart", null, null, null)).block();

        Map<String, byte[]> entries = unzip(file);
        assertThat(entries.keySet()).containsExactly("records/job_old.json", "platform.json", "manifest.json");
        EvidenceRecord stream = JSON.readValue(entries.get("platform.json"), EvidenceRecord.class);
        assertThat(stream.jobId()).isEqualTo(EvidenceRecord.PLATFORM);
        assertThat(HashChain.verify(stream.entries())).isEqualTo(-1);
        JsonNode manifest = JSON.readTree(entries.get("manifest.json"));
        assertThat(manifest.get("files")).extracting(f -> f.get("name").asText()).containsExactly("records/job_old.json", "platform.json");
    }

    @Test
    void aPackOfARecordWithManyEntriesIsSpooledToAFileAndDiscardedAfter() throws IOException {
        int many = 5_000;
        for (int i = 0; i < many; i++) {
            store.append("job_big", EvidenceEntry.draft(NOW.minusSeconds(many - i), i == 0 ? EvidenceKind.REQUESTED : EvidenceKind.STEP, "ana",
                    JSON.createObjectNode().put("workflow", "patch-cve").put("step", "host-" + i).put("output", Signatures.sha256Hex(("out" + i).getBytes(StandardCharsets.UTF_8)))));
        }

        SpooledFile file = handler.exportPack(new FilterSpec("patch-cve", null, null, null)).block();

        assertThat(file.path()).startsWith(spool).exists();
        assertThat(file.size()).isEqualTo(Files.size(file.path())).isGreaterThan(64 * 1024);
        Map<String, byte[]> entries = unzip(file);
        EvidenceRecord big = JSON.readValue(entries.get("records/job_big.json"), EvidenceRecord.class);
        assertThat(big.entries()).hasSize(many);
        assertThat(HashChain.verify(big.entries())).isEqualTo(-1);
        JsonNode manifest = JSON.readTree(entries.get("manifest.json"));
        assertThat(manifest.get("files").get(0).get("sha256").asText()).isEqualTo(Signatures.sha256Hex(entries.get("records/job_big.json")));
        assertThat(signer.verify(HashChain.canonical(manifest.get("files")), manifest.get("signature").asText())).isTrue();

        file.discard();
        assertThat(file.path()).doesNotExist();
        file.discard();
    }

    @Test
    void aPackThatFailsHalfWrittenLeavesNoFileBehind() throws IOException {
        ObjectMapper failing = JSON.copy().registerModule(new SimpleModule().addSerializer(EvidenceRecord.class, new JsonSerializer<EvidenceRecord>() {
            @Override
            public void serialize(EvidenceRecord value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeStartObject();
                gen.writeStringField("jobId", value.jobId());
                throw new IOException("the disk is full");
            }
        }));

        StepVerifier.create(new AuditQueryHandler(store, signer, clock, failing, spool).exportPack(null))
                .expectErrorMessage("the disk is full")
                .verify();
        try (var left = Files.list(spool)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void packsLeftUnsentForAnHourAreSweptWhenTheNextOneIsSpooled() throws IOException {
        Path stale = Files.writeString(spool.resolve("evidence-pack-stale.zip"), "PK");
        Files.setLastModifiedTime(stale, FileTime.from(NOW.minus(Duration.ofMinutes(61))));
        Path sending = Files.writeString(spool.resolve("evidence-pack-sending.zip"), "PK");
        Files.setLastModifiedTime(sending, FileTime.from(NOW.minus(Duration.ofMinutes(5))));
        Path other = Files.writeString(spool.resolve("notes.txt"), "not a pack");
        Files.setLastModifiedTime(other, FileTime.from(NOW.minus(Duration.ofDays(2))));

        SpooledFile file = handler.exportPack(null).block();

        assertThat(stale).doesNotExist();
        assertThat(sending).exists();
        assertThat(other).exists();
        assertThat(file.path()).exists();
    }

    @Test
    void theDemoPropertiesCarryTheSigningKey() {
        PlatformProperties props = new PlatformProperties();

        assertThat(props.getAudit().getSigningKey()).isEmpty();
        assertThat(props.getAudit().getRetentionYears()).isEqualTo(7);
    }

    static Map<String, byte[]> unzip(SpooledFile file) throws IOException {
        return unzip(Files.readAllBytes(file.path()));
    }

    static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }
}

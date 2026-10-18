package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.domain.Signatures;
import com.iaf.platform.common.domain.Hashes;
import com.iaf.platform.common.domain.NotFoundException;

class FileSystemEvidenceStoreTest {

    static final Instant AT = Instant.parse("2026-10-17T10:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<PosixFilePermission> READ_ONLY = List.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    @TempDir
    Path dir;

    FileSystemEvidenceStore store() {
        return new FileSystemEvidenceStore(dir, 7);
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static EvidenceEntry requested(Instant at, String workflow) {
        return EvidenceEntry.draft(at, EvidenceKind.REQUESTED, "ana",
                json("{\"workflow\":\"" + workflow + "\",\"version\":\"1.2.0\",\"requester\":\"ana\",\"channel\":\"api\"}"));
    }

    static EvidenceEntry step(String name) {
        return EvidenceEntry.draft(AT.plusSeconds(5), EvidenceKind.STEP, "platform", json("{\"step\":\"" + name + "\",\"status\":\"succeeded\"}"));
    }

    static EvidenceEntry closed() {
        return EvidenceEntry.draft(AT.plusSeconds(30), EvidenceKind.CLOSED, "platform",
                json("{\"status\":\"succeeded\",\"retention\":{\"until\":\"2033-10-17T10:00:30Z\"}}"));
    }

    static EvidenceRecord.Seal seal(Instant at, String signature, EvidenceEntry head) {
        return new EvidenceRecord.Seal(at, signature, "key-1", head.seq(), head.hash());
    }

    @Test
    void writesEachEntryOnceAsAReadOnlyFileWithItsRetention() throws IOException {
        FileSystemEvidenceStore store = store();

        EvidenceEntry first = store.append("job_1", requested(AT, "vm-create"));
        EvidenceEntry second = store.append("job_1", step("create"));

        assertThat(first.seq()).isEqualTo(1);
        assertThat(second.seq()).isEqualTo(2);
        assertThat(second.previous()).isEqualTo(first.hash());
        Path file = dir.resolve("evidence/job_1/entries/0000000001.json");
        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrderElementsOf(READ_ONLY);
        assertThat(JSON.readTree(file.toFile()).get("hash").asText()).isEqualTo(first.hash());
        JsonNode retention = JSON.readTree(dir.resolve("evidence/job_1/entries/0000000001.json.retention.json").toFile());
        assertThat(retention.get("retainUntil").asText()).isEqualTo("2033-10-17T10:00:00Z");
        assertThat(retention.get("sha256").asText()).isEqualTo(Signatures.sha256Hex(Files.readAllBytes(file)));
        assertThat(retention.get("mode").asText()).isEqualTo("write-once");
        try (Stream<Path> files = Files.list(dir.resolve("evidence/job_1/entries"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder(
                    "0000000001.json", "0000000001.json.retention.json", "0000000002.json", "0000000002.json.retention.json");
        }
    }

    @Test
    void anObjectIsNeverOverwritten() {
        FileSystemWormObjects objects = new FileSystemWormObjects(dir);

        assertThat(objects.putIfAbsent("a/b.json", "one".getBytes(), AT)).isTrue();
        assertThat(objects.putIfAbsent("a/b.json", "two".getBytes(), AT.plusSeconds(1))).isFalse();

        assertThat(objects.get("a/b.json")).hasValueSatisfying(b -> assertThat(new String(b)).isEqualTo("one"));
        assertThat(objects.retainUntil("a/b.json")).contains(AT);
        assertThat(objects.list("a/")).containsExactly("a/b.json");
    }

    @Test
    void keysThatWouldLeaveTheFolderAreRefused() {
        FileSystemWormObjects objects = new FileSystemWormObjects(dir);

        assertThatThrownBy(() -> objects.putIfAbsent("../outside.json", new byte[0], AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> objects.get("evidence/../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> objects.putIfAbsent("/absolute.json", new byte[0], AT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(dir.getParent().resolve("outside.json")).doesNotExist();
    }

    @Test
    void aRestartedStoreRebuildsTheRecordFromItsFiles() {
        FileSystemEvidenceStore store = store();
        store.append("job_1", requested(AT, "vm-create"));
        store.append("job_1", EvidenceEntry.draft(AT.plusSeconds(2), EvidenceKind.CHANGE, "platform",
                json("{\"id\":\"CHG0001\",\"url\":\"https://itsm.example/CHG0001\"}")));
        EvidenceEntry last = store.append("job_1", closed());
        EvidenceRecord.Seal seal = seal(AT.plusSeconds(3600), "sig-1", last);
        store.seal("job_1", seal);

        EvidenceRecord r = store().record("job_1").orElseThrow();

        assertThat(r.entries()).extracting(EvidenceEntry::seq).containsExactly(1, 2, 3);
        assertThat(HashChain.verify(r.entries())).isEqualTo(-1);
        assertThat(r.workflow()).isEqualTo("vm-create");
        assertThat(r.version()).isEqualTo("1.2.0");
        assertThat(r.requester()).isEqualTo("ana");
        assertThat(r.channel()).isEqualTo("api");
        assertThat(r.change()).isEqualTo(new EvidenceRecord.Change("CHG0001", "https://itsm.example/CHG0001"));
        assertThat(r.retention().until()).isEqualTo(Instant.parse("2033-10-17T10:00:30Z"));
        assertThat(r.latestHash()).isEqualTo(last.hash());
        assertThat(r.sealed()).isEqualTo(seal);
        assertThat(r.status()).isEqualTo("succeeded");
    }

    @Test
    void aSealedRecordKeepsItsFirstSealAndRefusesLateEntries() {
        FileSystemEvidenceStore store = store();
        store.append("job_1", requested(AT, "vm-create"));
        EvidenceEntry last = store.append("job_1", closed());
        EvidenceRecord.Seal first = seal(AT.plusSeconds(3600), "sig-1", last);

        store.seal("job_1", first);
        EvidenceRecord again = store.seal("job_1", seal(AT.plusSeconds(7200), "sig-2", last));

        assertThat(again.sealed()).isEqualTo(first);
        assertThatThrownBy(() -> store.append("job_1", step("late"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("sealed");
        assertThatThrownBy(() -> store().append("job_1", step("late"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("sealed");
        assertThat(store().record("job_1").orElseThrow().sealed()).isEqualTo(first);
    }

    @Test
    void aRecordSealedByAnotherInstanceRefusesLateEntriesHereToo() {
        FileSystemEvidenceStore here = store();
        here.append("job_1", requested(AT, "vm-create"));
        EvidenceEntry last = here.append("job_1", closed());

        store().seal("job_1", seal(AT.plusSeconds(3600), "sig-1", last));

        assertThatThrownBy(() -> here.append("job_1", step("late"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("sealed");
        assertThat(here.record("job_1").orElseThrow().sealed()).isNotNull();
    }

    @Test
    void twoInstancesAppendingToOneRecordKeepOneLinearChain() {
        FileSystemEvidenceStore a = store();
        FileSystemEvidenceStore b = store();
        a.append("job_1", requested(AT, "vm-create"));

        EvidenceEntry fromB = b.append("job_1", step("from b"));
        EvidenceEntry fromA = a.append("job_1", step("from a"));

        assertThat(fromB.seq()).isEqualTo(2);
        assertThat(fromA.seq()).isEqualTo(3);
        assertThat(fromA.previous()).isEqualTo(fromB.hash());
        EvidenceRecord r = store().record("job_1").orElseThrow();
        assertThat(r.entries()).extracting(e -> e.data().get("step") == null ? "-" : e.data().get("step").asText())
                .containsExactly("-", "from b", "from a");
        assertThat(HashChain.verify(r.entries())).isEqualTo(-1);
    }

    @Test
    void readingARecordPicksUpEntriesAnotherInstanceWrote() {
        FileSystemEvidenceStore a = store();
        a.append("job_1", requested(AT, "vm-create"));
        assertThat(a.record("job_1").orElseThrow().entries()).hasSize(1);

        store().append("job_1", step("elsewhere"));

        assertThat(a.record("job_1").orElseThrow().entries()).hasSize(2);
    }

    @Test
    void checkpointsAreKeptOncePerHeadAndSurviveARestart() throws IOException {
        FileSystemEvidenceStore store = store();
        store.append(EvidenceRecord.PLATFORM, EvidenceEntry.draft(AT, EvidenceKind.DECISION, "ana", json("{\"event\":\"workflow.published\"}")));
        EvidenceEntry head = store.append(EvidenceRecord.PLATFORM, EvidenceEntry.draft(AT.plusSeconds(1), EvidenceKind.DECISION, "ana",
                json("{\"event\":\"credential.stored\",\"name\":\"vault:kv/app\"}")));
        EvidenceRecord.Seal first = seal(AT.plusSeconds(3600), "sig-1", head);

        store.checkpoint(EvidenceRecord.PLATFORM, first);
        store.checkpoint(EvidenceRecord.PLATFORM, seal(AT.plusSeconds(7200), "sig-2", head));
        EvidenceEntry next = store.append(EvidenceRecord.PLATFORM, EvidenceEntry.draft(AT.plusSeconds(7300), EvidenceKind.DECISION, "bob",
                json("{\"event\":\"release.paused\"}")));
        EvidenceRecord.Seal second = seal(AT.plusSeconds(10_800), "sig-3", next);
        store.checkpoint(EvidenceRecord.PLATFORM, second);

        EvidenceRecord r = store().record(EvidenceRecord.PLATFORM).orElseThrow();
        assertThat(r.checkpoints()).containsExactly(first, second);
        assertThat(r.sealed()).isNull();
        assertThat(r.status()).isEqualTo("open");
        assertThat(Files.getPosixFilePermissions(dir.resolve("evidence/platform/checkpoints/0000000002.json")))
                .containsExactlyInAnyOrderElementsOf(READ_ONLY);
    }

    @Test
    void listsTheRecordsOnDiskNewestFirstAndFiltersThem() {
        FileSystemEvidenceStore store = store();
        store.append("job_old", requested(AT, "vm-create"));
        store.append("job_new", requested(AT.plusSeconds(3600), "db-backup"));
        store.append("release:rel_1", EvidenceEntry.draft(AT.minusSeconds(3600), EvidenceKind.DECISION, "ana",
                json("{\"event\":\"release.promoted\",\"workflow\":\"vm-create\",\"to\":\"1.3.0\"}")));

        FileSystemEvidenceStore restarted = store();

        assertThat(restarted.records(EvidenceFilter.ALL)).extracting(EvidenceRecord::jobId).containsExactly("job_new", "job_old", "release:rel_1");
        assertThat(restarted.records(new EvidenceFilter("vm-create", AT.minusSeconds(60), null, null))).extracting(EvidenceRecord::jobId)
                .containsExactly("job_old");
        assertThat(dir.resolve("evidence/release%3Arel_1")).isDirectory();
        assertThat(restarted.record("release:rel_1").orElseThrow().channel()).isEqualTo("release");
    }

    @Test
    void entryDataReadsBackToTheHashItWasChainedWith() {
        ObjectNode data = JSON.createObjectNode();
        data.put("amount", new BigDecimal("1.10"));
        data.put("big", 1e20);
        data.putNull("none");
        data.putArray("list").add(1).add("two");
        FileSystemEvidenceStore store = store();

        EvidenceEntry written = store.append("job_1", EvidenceEntry.draft(AT, EvidenceKind.REQUESTED, "ana", data));

        EvidenceRecord r = store().record("job_1").orElseThrow();
        assertThat(r.entries().get(0).hash()).isEqualTo(written.hash());
        assertThat(HashChain.verify(r.entries())).isEqualTo(-1);
        assertThat(data.get("amount").decimalValue()).isEqualByComparingTo("1.10");
    }

    @Test
    void shareTokensOutliveARestartAndAreKeptUntilTheyExpire() throws IOException {
        Instant expires = AT.plus(Duration.ofDays(7));
        ShareToken token = store().shareToken("job_1", expires);

        assertThat(store().findShareToken(token.token())).contains(token);
        String key = WormEvidenceStore.tokenKey(Hashes.sha256(token.token()));
        assertThat(new FileSystemWormObjects(dir).retainUntil(key)).contains(expires);
        assertThat(Files.readString(dir.resolve(key))).as("the object never holds the token").doesNotContain(token.token());
        assertThat(dir.resolve("share-tokens/" + token.token() + ".json")).doesNotExist();
        assertThat(store().findShareToken("../../evidence/job_1/seal")).isEmpty();
        assertThat(store().findShareToken("no-such-token-0123456789")).isEmpty();
        assertThat(store().findShareToken(null)).isEmpty();
    }

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        final AtomicReference<Instant> now;

        MovingClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    void sealedRecordsLeaveTheBoundedCacheAndReadBackFromTheirFiles() {
        WormEvidenceStore store = new WormEvidenceStore(new FileSystemWormObjects(dir), 7, Clock.fixed(AT, ZoneOffset.UTC), 2) {
        };
        for (int i = 1; i <= 5; i++) {
            String id = "job_" + i;
            store.append(id, requested(AT.plusSeconds(i), "vm-restart"));
            EvidenceEntry last = store.append(id, closed());
            store.seal(id, seal(AT.plusSeconds(60), "sig-" + i, last));
        }
        store.append("job_open", requested(AT, "vm-restart"));

        assertThat(store.cachedRecords()).as("the open record and the two most recently used sealed ones").isEqualTo(3);
        EvidenceRecord evicted = store.record("job_1").orElseThrow();
        assertThat(evicted.sealed().signature()).isEqualTo("sig-1");
        assertThat(evicted.entries()).hasSize(2);
        assertThatThrownBy(() -> store.append("job_1", step("late"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("is sealed");
        assertThat(store.cachedRecords()).isEqualTo(3);
        assertThat(store.records(EvidenceFilter.ALL)).hasSize(6);
    }

    @Test
    void anExpiredShareLinkLeavesTheCacheAndMintingSweepsTheRest() {
        MovingClock clock = new MovingClock(AT);
        FileSystemEvidenceStore store = new FileSystemEvidenceStore(dir, 7, clock);
        ShareToken first = store.shareToken("job_1", AT.plus(Duration.ofHours(1)));
        ShareToken second = store.shareToken("job_2", AT.plus(Duration.ofHours(2)));
        assertThat(store.cachedTokens()).isEqualTo(2);

        clock.now.set(AT.plus(Duration.ofMinutes(90)));
        assertThat(store.findShareToken(first.token())).as("still found, so the API can say it expired").contains(first);
        assertThat(store.cachedTokens()).isEqualTo(1);
        assertThat(store.findShareToken(second.token())).contains(second);

        clock.now.set(AT.plus(Duration.ofHours(3)));
        store.shareToken("job_3", AT.plus(Duration.ofDays(7)));
        assertThat(store.cachedTokens()).as("the expired link is swept when the next one is minted").isEqualTo(1);
    }

    @Test
    void anUnknownRecordIsNotFound() {
        FileSystemEvidenceStore store = store();
        EvidenceRecord.Seal seal = new EvidenceRecord.Seal(AT, "sig", "key-1", 1, "abc");

        assertThat(store.record("job_x")).isEmpty();
        assertThat(store.records(EvidenceFilter.ALL)).isEmpty();
        assertThatThrownBy(() -> store.seal("job_x", seal)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> store.checkpoint("job_x", seal)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void recordIdsBecomeOneSafeFolderNameAndComeBack() {
        assertThat(WormEvidenceStore.encode("release:rel_1")).isEqualTo("release%3Arel_1");
        assertThat(WormEvidenceStore.encode("../x/y")).isEqualTo("%2E%2E%2Fx%2Fy");
        assertThat(WormEvidenceStore.decode(WormEvidenceStore.encode("job_ä:1/2"))).isEqualTo("job_ä:1/2");
    }
}

package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.domain.NotFoundException;

/** What every {@link EvidenceStore} does, in memory and in a database. */
abstract class EvidenceStoreContract {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00.123456789Z");
    static final ObjectMapper JSON = new ObjectMapper();

    /** An empty store. */
    abstract EvidenceStore newStore();

    /** A new store over the same storage as the last one made; null when the storage does not outlive the store. */
    abstract EvidenceStore reopen();

    static EvidenceEntry requested(Instant at, String workflow, String requester) {
        ObjectNode data = JSON.createObjectNode().put("workflow", workflow).put("version", "1.2.0").put("requester", requester).put("channel", "rest");
        data.putObject("inputs").put("name", "web-01").put("cpu", 4).put("ratio", 0.75).put("ready", true).putNull("note")
                .put("label", "\"quoted\" ü ✓").putArray("tags").add("a").add(2);
        return EvidenceEntry.draft(at, EvidenceKind.REQUESTED, requester, data);
    }

    static EvidenceEntry step(Instant at) {
        return EvidenceEntry.draft(at, EvidenceKind.STEP, "platform", JSON.createObjectNode().put("step", "provision").put("status", "succeeded"));
    }

    static EvidenceEntry closed(Instant at, Instant keepUntil) {
        ObjectNode data = JSON.createObjectNode().put("status", "succeeded");
        data.putObject("retention").put("until", keepUntil.toString());
        return EvidenceEntry.draft(at, EvidenceKind.CLOSED, "platform", data);
    }

    static String text(JsonNode node) {
        return node == null ? null : node.toString();
    }

    @Test
    void chainsEntriesAndOpensTheRecordWithTheFirst() {
        EvidenceStore store = newStore();
        EvidenceEntry first = store.append("job_1", requested(T0, "vm-restart", "ana"));
        EvidenceEntry second = store.append("job_1", step(T0.plusNanos(1)));
        EvidenceEntry third = store.append("job_1", closed(T0.plusSeconds(5), Instant.parse("2033-10-17T00:00:00Z")));

        assertThat(List.of(first.seq(), second.seq(), third.seq())).containsExactly(1, 2, 3);
        assertThat(first.previous()).isEqualTo(HashChain.GENESIS);
        assertThat(second.previous()).isEqualTo(first.hash());
        assertThat(third.previous()).isEqualTo(second.hash());

        EvidenceRecord record = store.record("job_1").orElseThrow();
        assertThat(record.workflow()).isEqualTo("vm-restart");
        assertThat(record.version()).isEqualTo("1.2.0");
        assertThat(record.requester()).isEqualTo("ana");
        assertThat(record.channel()).isEqualTo("rest");
        assertThat(record.latestHash()).isEqualTo(third.hash());
        assertThat(record.retention().until()).isEqualTo(Instant.parse("2033-10-17T00:00:00Z"));
        assertThat(record.at()).isEqualTo(T0);
        assertThat(record.status()).isEqualTo("succeeded");
        assertThat(record.entries()).extracting(EvidenceEntry::hash).containsExactly(first.hash(), second.hash(), third.hash());
        assertThat(HashChain.verify(record.entries())).isEqualTo(-1);
    }

    @Test
    void readsEveryEntryBackExactly() {
        EvidenceStore store = newStore();
        EvidenceEntry first = store.append("job_1", requested(T0, "vm-restart", "ana"));
        EvidenceEntry change = store.append("job_1", EvidenceEntry.draft(T0.plusMillis(1), EvidenceKind.CHANGE, "platform",
                JSON.createObjectNode().put("id", "CHG0042").put("url", "https://changes.example/CHG0042")));
        EvidenceEntry bare = store.append("job_1", EvidenceEntry.draft(null, EvidenceKind.STEP, null, null));

        EvidenceRecord record = store.record("job_1").orElseThrow();
        assertThat(record.change()).isEqualTo(new EvidenceRecord.Change("CHG0042", "https://changes.example/CHG0042"));
        List<EvidenceEntry> entries = record.entries();
        assertThat(entries).hasSize(3);
        for (int i = 0; i < 3; i++) {
            EvidenceEntry expected = List.of(first, change, bare).get(i);
            EvidenceEntry actual = entries.get(i);
            assertThat(actual.seq()).isEqualTo(expected.seq());
            assertThat(actual.at()).isEqualTo(expected.at());
            assertThat(actual.kind()).isEqualTo(expected.kind());
            assertThat(actual.actor()).isEqualTo(expected.actor());
            assertThat(text(actual.data())).isEqualTo(text(expected.data()));
            assertThat(actual.hash()).isEqualTo(expected.hash());
            assertThat(actual.previous()).isEqualTo(expected.previous());
        }
        assertThat(HashChain.verify(entries)).isEqualTo(-1);
    }

    @Test
    void aReleaseRecordIsOnTheReleaseChannel() {
        EvidenceStore store = newStore();
        store.append("release:rel_1", EvidenceEntry.draft(T0, EvidenceKind.DECISION, "ana",
                JSON.createObjectNode().put("workflow", "vm-restart").put("to", "1.3.0").put("event", "release.promoted")));
        EvidenceRecord record = store.record("release:rel_1").orElseThrow();
        assertThat(record.channel()).isEqualTo("release");
        assertThat(record.version()).isEqualTo("1.3.0");
        assertThat(record.status()).isEqualTo("promoted");
    }

    @Test
    void anUnknownJobHasNoRecord() {
        EvidenceStore store = newStore();
        assertThat(store.record("job_unknown")).isEmpty();
        assertThat(store.record(null)).isEmpty();
        assertThat(store.records(EvidenceFilter.ALL)).isEmpty();
        assertThat(store.records(null)).isEmpty();
    }

    @Test
    void aSealedRecordRefusesLateEntries() {
        EvidenceStore store = newStore();
        store.append("job_1", requested(T0, "vm-restart", "ana"));
        store.append("job_1", closed(T0.plusSeconds(5), Instant.parse("2033-10-17T00:00:00Z")));
        store.seal("job_1", new EvidenceRecord.Seal(T0.plusSeconds(60), "sig-1", "key-1"));

        assertThatThrownBy(() -> store.append("job_1", step(T0.plusSeconds(61))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("evidence record job_1 is sealed; a late step entry is not accepted");
        assertThat(store.record("job_1").orElseThrow().entries()).hasSize(2);
    }

    @Test
    void aRecordKeepsItsFirstSeal() {
        EvidenceStore store = newStore();
        store.append("job_1", requested(T0, "vm-restart", "ana"));
        EvidenceRecord.Seal first = new EvidenceRecord.Seal(T0.plusSeconds(60).plusNanos(7), "sig-1", "key-1");

        EvidenceRecord sealed = store.seal("job_1", first);
        EvidenceRecord again = store.seal("job_1", new EvidenceRecord.Seal(T0.plusSeconds(120), "sig-2", "key-2"));

        assertThat(sealed.sealed()).isEqualTo(first);
        assertThat(again.sealed()).isEqualTo(first);
        assertThat(store.record("job_1").orElseThrow().sealed()).isEqualTo(first);
        assertThat(again.entries()).hasSize(1);
        assertThat(again.latestHash()).isEqualTo(sealed.latestHash());
    }

    @Test
    void checkpointsAnOpenRecordKeepingTheSameHeadOnceAndOutlivingTheStore() {
        EvidenceStore store = newStore();
        EvidenceEntry first = store.append("job_1", requested(T0, "vm-restart", "ana"));
        EvidenceRecord.Seal checkpoint = new EvidenceRecord.Seal(T0.plusSeconds(3600), "sig-1", "key-1", first.seq(), first.hash());

        EvidenceRecord once = store.checkpoint("job_1", checkpoint);
        EvidenceRecord twice = store.checkpoint("job_1", new EvidenceRecord.Seal(T0.plusSeconds(7200), "sig-2", "key-1", first.seq(), first.hash()));

        assertThat(once.checkpoints()).containsExactly(checkpoint);
        assertThat(twice.checkpoints()).as("the same head signed twice is kept once").containsExactly(checkpoint);
        assertThat(twice.sealed()).as("a checkpoint does not close the record").isNull();
        EvidenceEntry second = store.append("job_1", step(T0.plusSeconds(7201)));
        EvidenceRecord.Seal later = new EvidenceRecord.Seal(T0.plusSeconds(10800), "sig-3", "key-1", second.seq(), second.hash());
        assertThat(store.checkpoint("job_1", later).checkpoints()).containsExactly(checkpoint, later);
        assertThat(store.record("job_1").orElseThrow().checkpoints()).containsExactly(checkpoint, later);
        assertThatThrownBy(() -> store.checkpoint("job_unknown", later)).isInstanceOf(NotFoundException.class);

        EvidenceStore after = reopen();
        if (after != null) {
            assertThat(after.record("job_1").orElseThrow().checkpoints()).containsExactly(checkpoint, later);
        }
    }

    @Test
    void sealingAnUnknownJobIsNotFound() {
        EvidenceStore store = newStore();
        assertThatThrownBy(() -> store.seal("job_unknown", new EvidenceRecord.Seal(T0, "sig", "key")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("evidence record job_unknown not found");
    }

    @Test
    void listsMatchingRecordsNewestFirst() {
        EvidenceStore store = newStore();
        store.append("job_a", requested(T0, "vm-restart", "ana"));
        store.append("job_b", requested(T0.plusNanos(1), "vm-restart", "mateo"));
        store.append("job_c", requested(T0.plusSeconds(60), "create-vm", "ana"));
        store.append("job_d", requested(T0.plusSeconds(60), "create-vm", "ana"));
        store.append("job_z", EvidenceEntry.draft(null, EvidenceKind.STEP, "platform", null));
        store.append("job_a", step(T0.plusSeconds(300)));

        assertThat(store.records(EvidenceFilter.ALL)).extracting(EvidenceRecord::jobId).containsExactly("job_c", "job_d", "job_b", "job_a", "job_z");
        assertThat(store.records(null)).extracting(EvidenceRecord::jobId).containsExactly("job_c", "job_d", "job_b", "job_a", "job_z");
        assertThat(store.records(new EvidenceFilter("vm-restart", null, null, null))).extracting(EvidenceRecord::jobId).containsExactly("job_b", "job_a");
        assertThat(store.records(new EvidenceFilter(null, null, null, "ana"))).extracting(EvidenceRecord::jobId).containsExactly("job_c", "job_d", "job_a");
        assertThat(store.records(new EvidenceFilter(null, T0.plusNanos(1), null, null))).extracting(EvidenceRecord::jobId)
                .as("from is inclusive, to the nanosecond").containsExactly("job_c", "job_d", "job_b");
        assertThat(store.records(new EvidenceFilter(null, null, T0.plusNanos(1), null))).extracting(EvidenceRecord::jobId)
                .as("to is exclusive, to the nanosecond").containsExactly("job_a");
        assertThat(store.records(new EvidenceFilter("create-vm", T0, T0.plusSeconds(61), "ana"))).extracting(EvidenceRecord::jobId).containsExactly("job_c", "job_d");
        assertThat(store.records(new EvidenceFilter("nope", null, null, null))).isEmpty();

        EvidenceRecord a = store.records(new EvidenceFilter(null, null, T0.plusNanos(1), null)).get(0);
        assertThat(a.entries()).hasSize(2);
        assertThat(HashChain.verify(a.entries())).isEqualTo(-1);
    }

    @Test
    void aLaterRequestFillsInWhatTheFirstEntryLacked() {
        EvidenceStore store = newStore();
        store.append("job_1", step(T0));
        assertThat(store.records(new EvidenceFilter("vm-restart", null, null, null))).isEmpty();
        store.append("job_1", requested(T0.plusSeconds(1), "vm-restart", "ana"));
        assertThat(store.records(new EvidenceFilter("vm-restart", null, null, null))).extracting(EvidenceRecord::jobId).containsExactly("job_1");
        assertThat(store.records(new EvidenceFilter(null, null, null, "platform"))).extracting(EvidenceRecord::jobId)
                .as("the first entry's actor stays the requester").containsExactly("job_1");
        assertThat(store.record("job_1").orElseThrow().at()).as("still the first entry's time").isEqualTo(T0);
    }

    @Test
    void aShareTokenFindsItsRecordWithItsExpiry() {
        EvidenceStore store = newStore();
        store.append("job_1", requested(T0, "vm-restart", "ana"));
        Instant expires = T0.plusSeconds(7 * 86400).plusNanos(5);

        ShareToken token = store.shareToken("job_1", expires);
        ShareToken other = store.shareToken("job_1", expires);

        assertThat(token.token()).matches("[A-Za-z0-9_-]{32}");
        assertThat(other.token()).isNotEqualTo(token.token());
        assertThat(token.jobId()).isEqualTo("job_1");
        assertThat(token.expiresAt()).isEqualTo(expires);
        assertThat(store.findShareToken(token.token())).contains(token);
        assertThat(store.findShareToken(other.token())).contains(other);
        assertThat(store.findShareToken("not-a-token")).isEmpty();
        assertThat(store.findShareToken(null)).isEmpty();
    }

    @Test
    void concurrentAppendsMakeOneUnbrokenChainPerRecord() throws Exception {
        EvidenceStore store = newStore();
        int writers = 4;
        int each = 10;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                work.add(() -> {
                    for (int i = 0; i < each; i++) {
                        for (String job : List.of("job_x", "job_y")) {
                            store.append(job, EvidenceEntry.draft(T0.plusMillis(writer * 100L + i), EvidenceKind.STEP, "writer-" + writer,
                                    JSON.createObjectNode().put("writer", writer).put("i", i)));
                        }
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(work)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        for (String job : List.of("job_x", "job_y")) {
            EvidenceRecord record = store.record(job).orElseThrow();
            assertThat(record.entries()).hasSize(writers * each);
            assertThat(HashChain.verify(record.entries())).as(job).isEqualTo(-1);
            assertThat(record.latestHash()).isEqualTo(record.entries().get(writers * each - 1).hash());
        }
    }

    @Test
    void keepsEvidenceAcrossARestart() {
        EvidenceStore store = newStore();
        store.append("job_1", requested(T0, "vm-restart", "ana"));
        store.append("job_1", closed(T0.plusSeconds(5), Instant.parse("2033-10-17T00:00:00Z")));
        EvidenceRecord sealed = store.seal("job_1", new EvidenceRecord.Seal(T0.plusSeconds(60), "sig-1", "key-1"));
        ShareToken token = store.shareToken("job_1", T0.plusSeconds(86400));

        EvidenceStore after = reopen();
        assumeTrue(after != null, "this store does not outlive the process");
        EvidenceRecord read = after.record("job_1").orElseThrow();
        assertThat(read.sealed()).isEqualTo(sealed.sealed());
        assertThat(read.latestHash()).isEqualTo(sealed.latestHash());
        assertThat(read.entries()).extracting(EvidenceEntry::hash).containsExactlyElementsOf(sealed.entries().stream().map(EvidenceEntry::hash).toList());
        assertThat(HashChain.verify(read.entries())).isEqualTo(-1);
        assertThat(after.findShareToken(token.token())).contains(token);
        assertThatThrownBy(() -> after.append("job_1", step(T0.plusSeconds(90)))).isInstanceOf(IllegalStateException.class);
        assertThat(after.append("job_2", step(T0.plusSeconds(90))).seq()).isEqualTo(1);
    }
}

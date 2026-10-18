package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.audit.ports.outbound.EvidenceStore.Unsealed;
import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.common.domain.Hashes;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoEvidenceStoreTest extends EvidenceStoreContract {

    static final Instant KEEP_UNTIL = Instant.parse("2033-10-17T00:00:00Z");

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Override
    EvidenceStore newStore() {
        return new MongoEvidenceStore(db.mongo());
    }

    /** Another instance on the same database: the two share nothing but MongoDB. */
    @Override
    EvidenceStore reopen() {
        return new MongoEvidenceStore(db.mongo());
    }

    @Test
    void keepsOnlyTheHashOfAShareToken() {
        EvidenceStore store = newStore();
        store.append("job_1", requested(T0, "vm-restart", "ana"));
        ShareToken token = store.shareToken("job_1", T0.plusSeconds(86400));

        Document link = db.mongo().collection(MongoEvidenceStore.SHARE_LINKS).find().first();
        assertThat(link.get("_id")).isEqualTo(Hashes.sha256(token.token()));
        assertThat(link.toJson()).doesNotContain(token.token()).contains("job_1");
    }

    /** The store chains an entry as it will read back; what it reads back is what it was given. */
    @Test
    void anEntrysDataReadsBackAsGiven() throws Exception {
        EvidenceStore store = newStore();
        EvidenceEntry draft = EvidenceEntry.draft(T0, EvidenceKind.STEP, "ana", JSON.readTree("""
                {"step": "resize", "input": {"vm": "web-01", "cpu": 4, "bytes": 5000000000, "ratio": 0.75, "force": false, "note": null,
                 "nested": {"unset": null, "empty": {}, "list": []}, "tags": ["a", 2, null, {"k": "ü ✓"}], "dotted.key": 1, "$schema": "x"}}
                """));

        EvidenceEntry chained = store.append("job_1", draft);

        EvidenceEntry read = reopen().record("job_1").orElseThrow().entries().get(0);
        assertThat(read.data().toString()).isEqualTo(draft.data().toString());
        assertThat(chained.data().toString()).isEqualTo(draft.data().toString());
        assertThat(read.hash()).isEqualTo(chained.hash());
        assertThat(HashChain.verify(List.of(read))).isEqualTo(-1);
    }

    @Test
    void onlyEverInsertsEntries() {
        EvidenceStore store = newStore();
        EvidenceEntry first = store.append("job_1", requested(T0, "vm-restart", "ana"));
        List<Document> before = entries("job_1");
        store.checkpoint("job_1", new EvidenceRecord.Seal(T0.plusSeconds(1), "sig-0", "key-1", first.seq(), first.hash()));
        store.append("job_1", step(T0.plusSeconds(2)));
        EvidenceEntry closed = store.append("job_1", closed(T0.plusSeconds(5), KEEP_UNTIL));
        store.seal("job_1", new EvidenceRecord.Seal(T0.plusSeconds(60), "sig-1", "key-1", closed.seq(), closed.hash()));
        assertThatThrownBy(() -> store.append("job_1", step(T0.plusSeconds(61)))).isInstanceOf(IllegalStateException.class);
        reopen().seal("job_1", new EvidenceRecord.Seal(T0.plusSeconds(120), "sig-2", "key-2"));

        List<Document> after = entries("job_1");
        assertThat(after).hasSize(3);
        assertThat(after.get(0)).as("an entry stays exactly as it was inserted").isEqualTo(before.get(0));
        EvidenceRecord record = store.record("job_1").orElseThrow();
        assertThat(record.entries()).hasSize(3);
        assertThat(HashChain.verify(record.entries())).isEqualTo(-1);
        assertThat(record.sealed().signature()).isEqualTo("sig-1");
    }

    @Test
    void aSecondEntryAtATakenSeqIsRefusedAndTheChainStaysIntact() {
        EvidenceStore store = newStore();
        EvidenceEntry first = store.append("job_1", requested(T0, "vm-restart", "ana"));
        EvidenceEntry second = store.append("job_1", step(T0.plusSeconds(1)));
        EvidenceEntry forged = EvidenceEntry.draft(T0.plusSeconds(1), EvidenceKind.STEP, "mallory", JSON.createObjectNode().put("step", "forged"))
                .chained(2, first.hash());

        assertThatThrownBy(() -> db.mongo().collection(MongoEvidenceStore.ENTRIES).insertOne(
                new Document("_id", new Document("jobId", "job_1").append("seq", 2)).append(Mongo.DOC, db.mongo().doc(forged))))
                .isInstanceOfSatisfying(MongoException.class, e -> assertThat(Mongo.duplicateKey(e)).isTrue());
        // the unique index is on the parts, so the same key written the other way round is refused too
        assertThatThrownBy(() -> db.mongo().collection(MongoEvidenceStore.ENTRIES).insertOne(
                new Document("_id", new Document("seq", 2).append("jobId", "job_1")).append(Mongo.DOC, db.mongo().doc(forged))))
                .isInstanceOfSatisfying(MongoException.class, e -> assertThat(Mongo.duplicateKey(e)).isTrue());

        EvidenceRecord record = reopen().record("job_1").orElseThrow();
        assertThat(record.entries()).extracting(EvidenceEntry::hash).containsExactly(first.hash(), second.hash());
        assertThat(HashChain.verify(record.entries())).isEqualTo(-1);
        EvidenceEntry third = store.append("job_1", step(T0.plusSeconds(2)));
        assertThat(third.seq()).isEqualTo(3);
        assertThat(third.previous()).isEqualTo(second.hash());
    }

    @Test
    void aSecondSealChangesNothing() {
        EvidenceStore store = newStore();
        store.append("job_1", requested(T0, "vm-restart", "ana"));
        EvidenceEntry closed = store.append("job_1", closed(T0.plusSeconds(5), KEEP_UNTIL));
        EvidenceRecord.Seal first = new EvidenceRecord.Seal(T0.plusSeconds(60), "sig-1", "key-1", closed.seq(), closed.hash());
        store.seal("job_1", first);
        Document sealed = header("job_1");

        EvidenceRecord again = store.seal("job_1", new EvidenceRecord.Seal(T0.plusSeconds(120), "sig-2", "key-2", closed.seq(), closed.hash()));
        EvidenceRecord elsewhere = reopen().seal("job_1", new EvidenceRecord.Seal(T0.plusSeconds(180), "sig-3", "key-3"));

        assertThat(again.sealed()).isEqualTo(first);
        assertThat(elsewhere.sealed()).isEqualTo(first);
        assertThat(header("job_1")).as("the header document is untouched").isEqualTo(sealed);
        assertThat(db.mongo().collection(MongoEvidenceStore.RECORDS).updateOne(
                Filters.and(Filters.eq("_id", "job_1"), Filters.exists("seal", false)), Updates.set("seal", new Document("signature", "forged")))
                .getMatchedCount()).as("the seal update matches only a record without a seal").isZero();
        assertThat(reopen().record("job_1").orElseThrow().sealed()).isEqualTo(first);
    }

    @Test
    void sealingFromTwoInstancesAtOnceKeepsOneSeal() throws Exception {
        newStore().append("job_1", requested(T0, "vm-restart", "ana"));
        List<EvidenceStore> instances = List.of(newStore(), reopen(), reopen(), reopen());
        List<Callable<EvidenceRecord>> work = new ArrayList<>();
        for (int i = 0; i < instances.size(); i++) {
            EvidenceStore instance = instances.get(i);
            EvidenceRecord.Seal seal = new EvidenceRecord.Seal(T0.plusSeconds(60 + i), "sig-" + i, "key-1");
            work.add(() -> instance.seal("job_1", seal));
        }
        List<EvidenceRecord> results = run(work);

        EvidenceRecord.Seal kept = newStore().record("job_1").orElseThrow().sealed();
        assertThat(results).extracting(EvidenceRecord::sealed).containsOnly(kept);
    }

    @Test
    void aRewrittenEntryShowsInTheChain() {
        EvidenceStore store = newStore();
        store.append("job_1", requested(T0, "vm-restart", "ana"));
        store.append("job_1", step(T0.plusSeconds(1)));
        store.append("job_1", closed(T0.plusSeconds(5), KEEP_UNTIL));

        // what the platform's role refuses in production: someone with more rights edits an entry in place
        db.mongo().collection(MongoEvidenceStore.ENTRIES).updateOne(Filters.and(Filters.eq("_id.jobId", "job_1"), Filters.eq("_id.seq", 2)),
                Updates.set(Mongo.DOC + ".data.status", "failed"));

        EvidenceRecord record = reopen().record("job_1").orElseThrow();
        assertThat(record.entries()).hasSize(3);
        assertThat(HashChain.verify(record.entries())).as("the first entry that does not carry its own hash").isEqualTo(2);
    }

    @Test
    void twoInstancesAppendingToOneRecordMakeOneUnbrokenChain() throws Exception {
        List<EvidenceStore> instances = List.of(newStore(), reopen());
        int each = 15;
        List<Callable<EvidenceRecord>> work = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            int writer = w;
            EvidenceStore instance = instances.get(w % 2);
            work.add(() -> {
                for (int i = 0; i < each; i++) {
                    for (String job : List.of("job_x", EvidenceRecord.PLATFORM)) {
                        instance.append(job, EvidenceEntry.draft(T0.plusMillis(writer * 100L + i), EvidenceKind.STEP, "writer-" + writer,
                                JSON.createObjectNode().put("writer", writer).put("i", i)));
                    }
                }
                return null;
            });
        }
        run(work);

        for (String job : List.of("job_x", EvidenceRecord.PLATFORM)) {
            EvidenceRecord record = newStore().record(job).orElseThrow();
            assertThat(record.entries()).as(job).hasSize(4 * each);
            assertThat(HashChain.verify(record.entries())).as(job).isEqualTo(-1);
            assertThat(record.latestHash()).isEqualTo(record.entries().get(4 * each - 1).hash());
            assertThat(header(job).getInteger("count")).as("the header counted every entry").isEqualTo(4 * each);
        }
    }

    /**
     * An append inserts its entry, then grows the header: one cut off in between (the process
     * stopped) leaves the entry uncounted. Reads fold it in, the sealing run brings the header
     * forward, so the record is still sealed, and the next append chains after it.
     */
    @Test
    void anEntryItsHeaderDidNotCountIsReadSealedAndChainedAfter() {
        EvidenceStore store = newStore();
        EvidenceEntry first = store.append("job_1", requested(T0, "vm-restart", "ana"));
        EvidenceEntry cutOff = closed(T0.plusSeconds(5), KEEP_UNTIL).chained(2, first.hash());
        insertOnly("job_1", cutOff);
        insertOnly("job_2", requested(T0.plusSeconds(1), "create-vm", "mateo").chained(1, HashChain.GENESIS));

        EvidenceRecord read = store.record("job_1").orElseThrow();
        assertThat(read.entries()).hasSize(2);
        assertThat(read.latestHash()).isEqualTo(cutOff.hash());
        assertThat(read.status()).isEqualTo("succeeded");
        assertThat(read.retention().until()).isEqualTo(KEEP_UNTIL);
        assertThat(store.records(new EvidenceFilter("vm-restart", null, null, null))).singleElement()
                .satisfies(r -> assertThat(r.latestHash()).isEqualTo(cutOff.hash()));
        assertThat(store.record("job_2").orElseThrow().workflow()).as("a record whose header was never written").isEqualTo("create-vm");

        Unsealed unsealed = store.unsealed().stream().filter(u -> u.jobId().equals("job_1")).findFirst().orElseThrow();
        assertThat(unsealed.closed()).isTrue();
        assertThat(unsealed.entries()).isEqualTo(2);
        assertThat(unsealed.latestHash()).isEqualTo(cutOff.hash());
        assertThat(header("job_1").getInteger("count")).as("the sealing run brought the header forward").isEqualTo(2);

        EvidenceEntry next = store.append("job_2", step(T0.plusSeconds(2)));
        assertThat(next.seq()).isEqualTo(2);
        assertThat(header("job_2").getInteger("count")).isEqualTo(2);
        assertThat(HashChain.verify(store.record("job_2").orElseThrow().entries())).isEqualTo(-1);
        assertThat(store.recordIds(EvidenceFilter.ALL)).containsExactly("job_2", "job_1");
    }

    @Test
    void checkpointsHaveTheirOwnDocumentsKeyedByTheHeadTheySign() {
        EvidenceStore store = newStore();
        EvidenceEntry first = store.append(EvidenceRecord.PLATFORM, EvidenceEntry.draft(T0, EvidenceKind.DECISION, "ana",
                JSON.createObjectNode().put("event", "workflow.published")));
        store.checkpoint(EvidenceRecord.PLATFORM, new EvidenceRecord.Seal(T0.plusSeconds(3600), "sig-1", "key-1", first.seq(), first.hash()));
        store.checkpoint(EvidenceRecord.PLATFORM, new EvidenceRecord.Seal(T0.plusSeconds(7200), "sig-2", "key-1", first.seq(), first.hash()));

        List<Document> checkpoints = db.mongo().collection(MongoEvidenceStore.CHECKPOINTS).find().into(new ArrayList<>());
        assertThat(checkpoints).singleElement().satisfies(d -> {
            assertThat(d.get("_id")).isEqualTo(new Document("jobId", EvidenceRecord.PLATFORM).append("seq", 1));
            assertThat(d.get(Mongo.DOC, Document.class).getString("signature")).isEqualTo("sig-1");
        });
        assertThat(header(EvidenceRecord.PLATFORM)).doesNotContainKey("checkpoints");
        assertThat(header(EvidenceRecord.PLATFORM).get(Mongo.DOC, Document.class)).doesNotContainKey("checkpoints");
    }

    private void insertOnly(String jobId, EvidenceEntry chained) {
        db.mongo().collection(MongoEvidenceStore.ENTRIES).insertOne(new Document("_id", new Document("jobId", jobId).append("seq", chained.seq()))
                .append(Mongo.DOC, db.mongo().doc(chained)));
    }

    private List<Document> entries(String jobId) {
        return db.mongo().collection(MongoEvidenceStore.ENTRIES).find(Filters.eq("_id.jobId", jobId)).sort(Sorts.ascending("_id.seq"))
                .into(new ArrayList<>());
    }

    private Document header(String jobId) {
        return db.mongo().collection(MongoEvidenceStore.RECORDS).find(Filters.eq("_id", jobId)).first();
    }

    private static <T> List<T> run(List<Callable<T>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        try {
            List<T> results = new ArrayList<>();
            for (Future<T> f : pool.invokeAll(work)) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}

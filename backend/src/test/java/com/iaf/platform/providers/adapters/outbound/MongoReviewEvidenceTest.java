package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.providers.domain.ReviewEvidence;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;
import com.iaf.platform.providers.ports.outbound.ReviewEvidencePort;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoReviewEvidenceTest extends ReviewEvidenceContract {

    static final int MAX = InMemoryReviewEvidence.MAX_CONNECTIONS;

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Override
    ReviewEvidencePort newStore() {
        return new MongoReviewEvidence(db.mongo());
    }

    @Override
    ReviewEvidencePort reopen() {
        return new MongoReviewEvidence(new Mongo(db.database()));
    }

    @Test
    void theBoundDeletesTheOldestDocumentsInsteadOfHidingThem() {
        for (int i = 0; i < MAX + 5; i++) {
            store.recordConnection("fleet", connection("dev", true, i, T0.plusSeconds(i)));
        }
        store.recordConnection("vault", connection("dev", true, 1, T0));
        assertThat(documents("fleet", MongoReviewEvidence.CONNECTION)).isEqualTo(MAX);
        assertThat(documents("vault", MongoReviewEvidence.CONNECTION)).isEqualTo(1);
        assertThat(documents("fleet", MongoReviewEvidence.TRY)).isZero();
    }

    @Test
    void theDroppedCountNeedsNoDocumentOfItsOwn() {
        for (int i = 0; i < MAX + 7; i++) {
            store.recordConnection("fleet", connection("dev", true, i, T0.plusSeconds(i)));
        }
        assertThat(db.mongo().collection(MongoReviewEvidence.COLLECTION).countDocuments(Filters.eq("provider", "fleet")))
                .as("the entries are all there is").isEqualTo(MAX);
        assertThat(numbers("fleet", MongoReviewEvidence.CONNECTION)).as("numbered in the order recorded")
                .containsExactlyElementsOf(LongStream.rangeClosed(8, MAX + 7).boxed().toList());
        assertThat(store.evidence("fleet").droppedConnections()).isEqualTo(7);
        assertThat(store.evidence("fleet").connections()).hasSize(MAX);
    }

    @Test
    void aTryRecordedBeforeEndpointsWereKeptReadsBackWithoutThem() {
        ObjectNode legacy = Mongo.mapper().valueToTree(answered("vm.get", 10, T0));
        legacy.remove("endpoint");
        legacy.remove("credential");
        db.mongo().collection(MongoReviewEvidence.COLLECTION).insertOne(new Document("provider", "fleet").append("kind", MongoReviewEvidence.TRY)
                .append("seq", 1L).append("at", Mongo.date(T0)).append(Mongo.DOC, db.mongo().doc(legacy)));
        ActionTry t = store.evidence("fleet").tries().get(0);
        assertThat(t).isEqualTo(answered("vm.get", 10, T0));
        assertThat(t.endpoint()).isNull();
        assertThat(t.credential()).isNull();
    }

    @Test
    void aResponseComesBackWithItsNulls() {
        ObjectNode response = JsonNodeFactory.instance.objectNode().put("id", "vm-1").putNull("owner");
        response.putObject("network").putNull("ip");
        response.putArray("tags").addNull().add("web");
        ActionTry t = new ActionTry("vm.get", "dev", true, 200, 10, null, response, null, T0, "mateo", "https://fleet-dev.example.com", "vault:fleet/token");
        store.recordTry("fleet", t);
        assertThat(reopen().evidence("fleet").tries()).containsExactly(t);
    }

    @Test
    void anotherInstanceSeesEvidenceAtOnce() {
        ReviewEvidencePort other = reopen();
        store.recordTry("fleet", answered("vm.get", 80, T0));
        assertThat(other.evidence("fleet").tries()).containsExactly(answered("vm.get", 80, T0));
    }

    @Test
    void instancesRecordingAtOnceNeitherLoseNorRepeatAnEntry() throws Exception {
        // each write takes the next free number; the unique index makes a second claim on it fail and try the next
        ReviewEvidencePort other = reopen();
        int writers = 4;
        int each = 30;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                ReviewEvidencePort port = w % 2 == 0 ? store : other;
                long base = w * 1000L;
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < each; i++) {
                        port.recordConnection("fleet", connection("dev", true, base + i, T0.plusSeconds(i)));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int total = writers * each;
        ReviewEvidence fleet = other.evidence("fleet");
        assertThat(fleet.connections()).hasSize(MAX).doesNotHaveDuplicates();
        assertThat(fleet.droppedConnections()).isEqualTo(total - MAX);
        assertThat(numbers("fleet", MongoReviewEvidence.CONNECTION)).containsExactlyElementsOf(LongStream.rangeClosed(total - MAX + 1, total).boxed().toList());
        for (int w = 0; w < writers; w++) {
            long base = w * 1000L;
            assertThat(fleet.connections().stream().map(ConnectionTest::durationMs).filter(ms -> ms >= base && ms < base + 1000).toList())
                    .as("writer %d's entries in the order it recorded them", w).isSorted();
        }
    }

    @Test
    void entriesAWriteLeftBeyondTheBoundAreNeitherShownNorLeftUncounted() {
        for (int i = 0; i < MAX; i++) {
            store.recordConnection("fleet", connection("dev", true, i, T0.plusSeconds(i)));
        }
        // three writes that stopped after their insert, before deleting what fell out of the bound
        for (int i = MAX; i < MAX + 3; i++) {
            db.mongo().collection(MongoReviewEvidence.COLLECTION).insertOne(new Document("provider", "fleet").append("kind", MongoReviewEvidence.CONNECTION)
                    .append("seq", i + 1L).append("at", Mongo.date(T0.plusSeconds(i))).append(Mongo.DOC, db.mongo().doc(connection("dev", true, i, T0.plusSeconds(i)))));
        }
        assertThat(documents("fleet", MongoReviewEvidence.CONNECTION)).isEqualTo(MAX + 3);
        ReviewEvidence fleet = store.evidence("fleet");
        assertThat(fleet.connections()).hasSize(MAX);
        assertThat(fleet.connections().get(0).durationMs()).isEqualTo(3);
        assertThat(fleet.droppedConnections()).isEqualTo(3);

        store.recordConnection("fleet", connection("dev", true, MAX + 3, T0.plusSeconds(MAX + 3)));
        assertThat(documents("fleet", MongoReviewEvidence.CONNECTION)).as("the next write tidies up").isEqualTo(MAX);
        assertThat(store.evidence("fleet").droppedConnections()).isEqualTo(4);
    }

    private long documents(String provider, String kind) {
        return db.mongo().collection(MongoReviewEvidence.COLLECTION).countDocuments(Filters.and(Filters.eq("provider", provider), Filters.eq("kind", kind)));
    }

    private List<Long> numbers(String provider, String kind) {
        return db.mongo().collection(MongoReviewEvidence.COLLECTION).find(Filters.and(Filters.eq("provider", provider), Filters.eq("kind", kind)))
                .sort(Sorts.ascending("seq")).map(d -> ((Number) d.get("seq")).longValue()).into(new ArrayList<>());
    }
}

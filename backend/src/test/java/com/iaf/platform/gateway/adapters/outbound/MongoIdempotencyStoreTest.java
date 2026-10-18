package com.iaf.platform.gateway.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.gateway.domain.KeyedRequest;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;
import com.mongodb.client.MongoCollection;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoIdempotencyStoreTest extends IdempotencyStoreContract {

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Override
    IdempotencyStore newStore() {
        return new MongoIdempotencyStore(db.mongo());
    }

    MongoCollection<Document> keys() {
        return db.database().getCollection(MongoIdempotencyStore.COLLECTION);
    }

    @Test
    void anotherReplicaSeesTheClaimAndThenTheAnswer() {
        IdempotencyStore other = newStore();
        store.claim(request("ana", "k1", "fp1", T0), T0);
        assertThat(other.claim(request("ana", "k1", "fp2", T0), T0)).hasValueSatisfying(e -> {
            assertThat(e.fingerprint()).isEqualTo("fp1");
            assertThat(e.answered()).isFalse();
        });
        store.complete("ana", "k1", OK);
        assertThat(other.claim(request("ana", "k1", "fp1", T0), T0)).hasValueSatisfying(e -> assertThat(e.answer()).isEqualTo(OK));
        other.release("ana", "k1");
        assertThat(store.claim(request("ana", "k1", "fp1", T0), T0)).as("an answered key is never released").isPresent();
    }

    @Test
    void aTtlIndexDeletesAKeyOnceItsLifetimeHasPassedOnTheDatabasesClock() {
        assertThat(keys().listIndexes()).anySatisfy(index -> {
            assertThat(index.get("key", Document.class)).isEqualTo(new Document("expiresAt", 1));
            assertThat(((Number) index.get("expireAfterSeconds")).longValue()).isZero();
        });

        Instant before = Instant.now();
        store.claim(request("ana", "k1", "fp1", T0), T0);
        Instant after = Instant.now();

        Instant expiresAt = keys().find().first().getDate("expiresAt").toInstant();
        assertThat(expiresAt).as("the database's time when stored, plus the key's lifetime")
                .isBetween(before.plus(DAY).minusSeconds(60), after.plus(DAY).plusSeconds(60));
    }

    @Test
    void anExpiredKeyTheTtlMonitorHasNotDeletedYetReadsAsAbsentAndIsTakenOverInPlace() {
        MongoIdempotencyStore mongo = (MongoIdempotencyStore) store;
        mongo.claim(request("ana", "k1", "fp1", T0), T0);
        mongo.complete("ana", "k1", OK);
        Instant later = T0.plus(DAY).plusSeconds(1);

        assertThat(mongo.claim(request("ana", "k1", "fp2", later), later)).isEmpty();

        assertThat(mongo.size()).isEqualTo(1);
        Document stored = keys().find().first();
        assertThat(stored.get("status")).as("in flight again").isNull();
        assertThat(mongo.claim(request("ana", "k1", "fp3", later), later)).hasValueSatisfying(e -> {
            assertThat(e.fingerprint()).isEqualTo("fp2");
            assertThat(e.createdAt()).isEqualTo(later);
            assertThat(e.answered()).isFalse();
        });
    }

    @Test
    void twoReplicasRacingForANewKeyLetExactlyOneRequestProceed() throws Exception {
        IdempotencyStore[] replicas = {store, newStore()};
        List<Optional<KeyedRequest>> claims = race(16, i -> replicas[i % 2].claim(request("ana", "k-race", "fp" + i, T0), T0));

        assertThat(claims).filteredOn(Optional::isEmpty).hasSize(1);
        int winner = claims.indexOf(Optional.<KeyedRequest>empty());
        assertThat(claims).filteredOn(Optional::isPresent)
                .allSatisfy(c -> assertThat(c.get().fingerprint()).isEqualTo("fp" + winner));
        assertThat(((MongoIdempotencyStore) store).size()).isEqualTo(1);
    }

    @Test
    void twoReplicasRacingForAnExpiredKeyLetExactlyOneTakeItOver() throws Exception {
        store.claim(request("ana", "k-race", "fp-first", T0), T0);
        store.complete("ana", "k-race", OK);
        Instant later = T0.plus(DAY).plusSeconds(1);
        IdempotencyStore[] replicas = {store, newStore()};

        List<Optional<KeyedRequest>> claims = race(16, i -> replicas[i % 2].claim(request("ana", "k-race", "fp" + i, later), later));

        assertThat(claims).filteredOn(Optional::isEmpty).hasSize(1);
        int winner = claims.indexOf(Optional.<KeyedRequest>empty());
        assertThat(claims).filteredOn(Optional::isPresent).allSatisfy(c -> {
            assertThat(c.get().fingerprint()).isEqualTo("fp" + winner);
            assertThat(c.get().answered()).isFalse();
        });
    }

    interface Attempt<T> {
        T run(int i) throws Exception;
    }

    /** Runs {@code n} attempts at once, each on its own thread, and returns their results in order. */
    static <T> List<T> race(int n, Attempt<T> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<T>> work = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                work.add(() -> {
                    start.await();
                    return attempt.run(index);
                });
            }
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> w : work) {
                futures.add(pool.submit(w));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}

package com.iaf.platform.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.common.domain.TooManyRequestsException;
import com.iaf.platform.gateway.adapters.outbound.MongoQuotaStore;
import com.iaf.platform.gateway.ports.outbound.QuotaStore;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import reactor.test.StepVerifier;

/** The quotas over the MongoDB store: the same contract as in memory, and the buckets every replica shares. */
@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoJobQuotasTest extends JobQuotasContract {

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Override
    QuotaStore newStore() {
        return new MongoQuotaStore(db.mongo());
    }

    MongoCollection<Document> buckets() {
        return db.database().getCollection("quota_buckets");
    }

    @Test
    void everyReplicaDrawsFromTheSameBucket() {
        JobQuotas replicaA = quotas(quotas(60, 2));
        JobQuotas replicaB = new JobQuotas(quotas(60, 2), newStore(), clock);

        StepVerifier.create(replicaA.take(person("mateo", "team-compute"))).verifyComplete();
        StepVerifier.create(replicaB.take(person("ana", "team-compute"))).verifyComplete();
        StepVerifier.create(replicaA.take(person("mateo", "team-compute"))).expectError(TooManyRequestsException.class).verify();
        StepVerifier.create(replicaB.take(person("ana", "team-compute"))).expectError(TooManyRequestsException.class).verify();
    }

    @Test
    void aBucketKeepsItsTokensAndRefillsFromWhenItWasLastDrawn() {
        MongoQuotaStore mongo = (MongoQuotaStore) store;
        assertThat(mongo.take("team-data", 6, 3, T0)).isEmpty();
        assertThat(mongo.take("team-data", 6, 3, T0)).isEmpty();
        assertThat(mongo.tokensOf("team-data")).contains(1d);

        Instant later = T0.plusSeconds(20);   // a token every 10 s
        assertThat(mongo.take("team-data", 6, 3, later)).isEmpty();
        assertThat(mongo.take("team-data", 6, 3, later)).isEmpty();
        assertThat(mongo.take("team-data", 6, 3, later)).isEmpty();
        assertThat(mongo.take("team-data", 6, 3, later)).contains(Duration.ofSeconds(10));
    }

    @Test
    void aLoweredLimitNeverLeavesMoreTokensThanItAllows() {
        MongoQuotaStore mongo = (MongoQuotaStore) store;
        assertThat(mongo.take("team-data", 60, 5, T0)).isEmpty();
        assertThat(mongo.tokensOf("team-data")).contains(4d);

        assertThat(mongo.take("team-data", 60, 2, T0)).isEmpty();
        assertThat(mongo.tokensOf("team-data")).contains(1d);
        assertThat(mongo.take("team-data", 60, 2, T0)).isEmpty();
        assertThat(mongo.take("team-data", 60, 2, T0)).isPresent();
    }

    @Test
    void aBucketIdleForMoreThanADayStartsFullEvenBeforeTheTtlMonitorDeletesIt() {
        MongoQuotaStore mongo = (MongoQuotaStore) store;
        // one start a minute and two thousand at once: a day of refills alone does not fill it
        mongo.take("team-data", 1, 2000, T0);
        mongo.take("team-compute", 1, 2000, T0);
        buckets().updateMany(Filters.empty(), Updates.set("tokens", 0d));   // drained, as the replicas left them

        assertThat(mongo.take("team-compute", 1, 2000, T0.plus(Duration.ofHours(24)))).isEmpty();
        assertThat(mongo.tokensOf("team-compute")).as("idle a day exactly: refilled, not new").contains(1439d);
        assertThat(mongo.take("team-data", 1, 2000, T0.plus(Duration.ofHours(25)))).isEmpty();
        assertThat(mongo.tokensOf("team-data")).as("idle longer: as if it were gone").contains(1999d);
    }

    @Test
    void aTtlIndexDeletesABucketADayAfterItsLastTakeOnTheDatabasesClock() {
        assertThat(buckets().listIndexes()).anySatisfy(index -> {
            assertThat(index.get("key", Document.class)).isEqualTo(new Document("touchedAt", 1));
            assertThat(((Number) index.get("expireAfterSeconds")).longValue()).isEqualTo(Duration.ofDays(1).toSeconds());
        });

        Instant before = Instant.now();
        store.take("team-data", 60, 5, T0);
        Instant after = Instant.now();

        Instant touched = buckets().find().first().getDate("touchedAt").toInstant();
        assertThat(touched).isBetween(before.minusSeconds(60), after.plusSeconds(60));
    }

    @Test
    void aTokenGivenBackNeverRaisesABucketAboveItsBurstAndAMissingBucketStaysMissing() {
        MongoQuotaStore mongo = (MongoQuotaStore) store;
        mongo.take("team-data", 60, 2, T0);
        mongo.giveBack("team-data", 2);
        mongo.giveBack("team-data", 2);
        assertThat(mongo.tokensOf("team-data")).contains(2d);

        mongo.giveBack("team-nobody", 2);
        assertThat(mongo.tokensOf("team-nobody")).isEmpty();
    }

    @Test
    void twoReplicasRacingForTheLastTokenOfABucketHandItOutOnce() throws Exception {
        QuotaStore[] replicas = {store, newStore()};
        for (int round = 0; round < 10; round++) {
            String bucket = "team-" + round;
            assertThat(store.take(bucket, 60, 2, T0)).isEmpty();   // one token left

            List<Optional<Duration>> takes = race(16, i -> replicas[i % 2].take(bucket, 60, 2, T0));

            assertThat(takes).as("round %d", round).filteredOn(Optional::isEmpty).hasSize(1);
            assertThat(takes).filteredOn(Optional::isPresent).allSatisfy(wait -> assertThat(wait).contains(Duration.ofSeconds(1)));
            assertThat(((MongoQuotaStore) store).tokensOf(bucket)).contains(0d);
        }
    }

    @Test
    void manyTakersOnTwoReplicasNeverGetMoreThanTheBurst() throws Exception {
        QuotaStore[] replicas = {store, newStore()};

        List<Optional<Duration>> takes = race(24, i -> replicas[i % 2].take("team-data", 60, 5, T0));

        assertThat(takes).filteredOn(Optional::isEmpty).hasSize(5);
        assertThat(((MongoQuotaStore) store).tokensOf("team-data")).contains(0d);
    }

    interface Attempt<T> {
        T run(int i) throws Exception;
    }

    /** Runs {@code n} attempts at once, each on its own thread, and returns their results in order. */
    static <T> List<T> race(int n, Attempt<T> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return attempt.run(index);
                }));
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

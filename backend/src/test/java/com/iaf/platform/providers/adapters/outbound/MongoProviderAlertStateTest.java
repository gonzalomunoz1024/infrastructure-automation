package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.providers.ports.outbound.ProviderAlertStatePort;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoProviderAlertStateTest {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00.123456789Z");

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Test
    void nothingIsKeptBeforeTheFirstEvaluation() {
        assertThat(new MongoProviderAlertState(db.mongo()).lastEvaluatedAt()).isEmpty();
    }

    @Test
    void theExactInstantSurvivesARestart() {
        ProviderAlertStatePort state = new MongoProviderAlertState(db.mongo());
        state.lastEvaluatedAt(T0);
        assertThat(state.lastEvaluatedAt()).contains(T0);
        ProviderAlertStatePort restarted = new MongoProviderAlertState(new Mongo(db.database()));
        assertThat(restarted.lastEvaluatedAt()).contains(T0);
    }

    @Test
    void itOnlyMovesForwardAndStaysOneDocument() {
        ProviderAlertStatePort state = new MongoProviderAlertState(db.mongo());
        state.lastEvaluatedAt(T0);
        state.lastEvaluatedAt(T0.minusSeconds(60));
        assertThat(state.lastEvaluatedAt()).contains(T0);
        state.lastEvaluatedAt(T0.plusSeconds(60));
        state.lastEvaluatedAt(null);
        assertThat(state.lastEvaluatedAt()).contains(T0.plusSeconds(60));
        assertThat(db.mongo().collection(MongoProviderAlertState.COLLECTION).countDocuments()).isEqualTo(1);
        Document kept = db.mongo().collection(MongoProviderAlertState.COLLECTION).find().first();
        assertThat(kept.get("_id")).isEqualTo(MongoProviderAlertState.EVALUATOR);
        assertThat(kept.get(MongoProviderAlertState.AT)).as("the date for people reading follows the exact instant").isEqualTo(Date.from(T0.plusSeconds(60)));
    }

    @Test
    void instancesEvaluatingAtOnceKeepTheLatestInstant() throws Exception {
        // moving forward is one $max on the document: no earlier instant written last can win
        List<ProviderAlertStatePort> states = List.of(new MongoProviderAlertState(db.mongo()), new MongoProviderAlertState(new Mongo(db.database())));
        List<Instant> instants = new ArrayList<>(IntStream.range(0, 200).mapToObj(i -> T0.plusNanos(i * 1_000_001L)).toList());
        Collections.shuffle(instants);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                ProviderAlertStatePort state = states.get(w % 2);
                List<Instant> mine = instants.subList(w * 50, (w + 1) * 50);
                done.add(pool.submit(() -> {
                    start.await();
                    mine.forEach(state::lastEvaluatedAt);
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
        Instant latest = T0.plusNanos(199 * 1_000_001L);
        assertThat(states.get(0).lastEvaluatedAt()).contains(latest);
        assertThat(db.mongo().collection(MongoProviderAlertState.COLLECTION).find().first().get(MongoProviderAlertState.AT)).isEqualTo(Date.from(latest));
    }
}

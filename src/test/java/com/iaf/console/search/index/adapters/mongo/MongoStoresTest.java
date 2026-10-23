package com.iaf.console.search.index.adapters.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.search.index.domain.Checkpoint;
import com.iaf.console.search.index.domain.RebuildRun;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

import reactor.core.publisher.Mono;

/** The MongoDB stores, against the database {@code CONSOLE_TEST_MONGO_URI} names (CI starts one); skipped without it. */
@EnabledIf("com.iaf.console.search.index.adapters.mongo.MongoStoresTest#available")
class MongoStoresTest {

    private static MongoClient client;
    private static Mongo mongo;

    static boolean available() {
        String uri = System.getenv("CONSOLE_TEST_MONGO_URI");
        return uri != null && !uri.isBlank();
    }

    @BeforeAll
    static void connect() {
        client = MongoClients.create(System.getenv("CONSOLE_TEST_MONGO_URI").trim());
        mongo = new Mongo(client.getDatabase("console_search_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)));
    }

    @AfterAll
    static void drop() {
        if (mongo != null) {
            Mono.from(mongo.database().drop()).block();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    void rebuildRunsAreKeptAndFound() {
        MongoRebuildRuns runs = new MongoRebuildRuns(mongo);
        runs.ensureIndexes().block();
        Instant at = Instant.parse("2026-10-23T12:00:00Z");
        RebuildRun run = RebuildRun.start("rb_1", "123456789012", at, "key-1");
        runs.save(run).block();
        assertThat(runs.find("rb_1").block()).isEqualTo(run);
        assertThat(runs.findRunning().block().runId()).isEqualTo("rb_1");
        assertThat(runs.findByIdempotencyKey("key-1").block().runId()).isEqualTo("rb_1");
        runs.save(run.succeeded(at.plusSeconds(5), 42)).block();
        assertThat(runs.findRunning().blockOptional()).isEmpty();
        assertThat(runs.find("rb_1").block().indexed()).isEqualTo(42);
        assertThat(runs.find("rb_1").block().idempotencyKey()).isEqualTo("key-1");
        assertThat(runs.find("rb_nope").blockOptional()).isEmpty();
    }

    @Test
    void checkpointsAreKept() {
        MongoCheckpoints checkpoints = new MongoCheckpoints(mongo);
        checkpoints.ensureIndexes().block();
        Instant at = Instant.parse("2026-10-23T12:00:00Z");
        Checkpoint c = Checkpoint.empty("events").advanced(at, at.plusSeconds(1));
        checkpoints.save(c).block();
        assertThat(checkpoints.find("events").block()).isEqualTo(c);
        checkpoints.save(c.advanced(at.plusSeconds(10), at.plusSeconds(11))).block();
        assertThat(checkpoints.find("events").block().eventAt()).isEqualTo(at.plusSeconds(10));
        assertThat(checkpoints.find("poll:1").blockOptional()).isEmpty();
    }
}

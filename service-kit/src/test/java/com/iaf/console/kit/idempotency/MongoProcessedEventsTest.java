package com.iaf.console.kit.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoTestSupport;
import com.mongodb.reactivestreams.client.MongoClient;

@EnabledIf("com.iaf.console.kit.mongo.MongoTestSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoProcessedEventsTest {

    private MongoClient client;
    private Mongo mongo;
    private MongoProcessedEvents processed;

    @BeforeAll
    void connect() {
        client = MongoTestSupport.client();
    }

    /** A fresh database per test, so no test sees another's keys or counters. */
    @BeforeEach
    void freshStore() {
        mongo = new Mongo(client.getDatabase(MongoTestSupport.freshDatabase("kit_processed")));
        processed = new MongoProcessedEvents(mongo);
        processed.ensureIndexes().block(Duration.ofSeconds(30));
    }

    @AfterEach
    void dropDatabase() {
        MongoTestSupport.drop(mongo);
    }

    @AfterAll
    void disconnect() {
        client.close();
    }

    @Test
    void remembersAKeyOnce() {
        Instant now = Instant.now();
        assertThat(processed.markIfNew("job.finished:j-1:1", now, Duration.ofDays(1)).block()).isTrue();
        assertThat(processed.markIfNew("job.finished:j-1:1", now, Duration.ofDays(1)).block()).isFalse();
        processed.forget("job.finished:j-1:1").block();
        assertThat(processed.markIfNew("job.finished:j-1:1", now, Duration.ofDays(1)).block()).isTrue();
    }

    @Test
    void sequencesCountFromOne() {
        assertThat(mongo.nextSequence("test").block()).isEqualTo(1L);
        assertThat(mongo.nextSequence("test").block()).isEqualTo(2L);
        assertThat(mongo.nextSequence("other").block()).isEqualTo(1L);
    }
}

package com.iaf.console.kit.outbox;

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
class MongoOutboxTest {

    private MongoClient client;
    private Mongo mongo;
    private MongoOutbox outbox;

    @BeforeAll
    void connect() {
        client = MongoTestSupport.client();
    }

    /** A fresh database per test: the sequence starts at 1 whatever order the tests run in. */
    @BeforeEach
    void freshOutbox() {
        mongo = new Mongo(client.getDatabase(MongoTestSupport.freshDatabase("kit_outbox")));
        outbox = new MongoOutbox(mongo);
        outbox.ensureIndexes().block(Duration.ofSeconds(30));
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
    void appendsInSequenceAndOncePerKey() {
        OutboxEntry a = outbox.append("console.jobs", "job-1", InMemoryOutboxTest.event("m:1:1")).block();
        OutboxEntry b = outbox.append("console.jobs", "job-1", InMemoryOutboxTest.event("m:1:2")).block();
        OutboxEntry again = outbox.append("console.jobs", "job-1", InMemoryOutboxTest.event("m:1:1").withId("other")).block();

        assertThat(a.seq()).isEqualTo(1);
        assertThat(b.seq()).isEqualTo(2);
        assertThat(again.seq()).isEqualTo(1);
        assertThat(again.event().id()).isEqualTo("id-m:1:1");
        assertThat(again.event().seq()).isEqualTo(1L);
        assertThat(outbox.highWater().block()).isEqualTo(2L);
        assertThat(outbox.pending(10).map(OutboxEntry::key).collectList().block()).containsExactly("m:1:1", "m:1:2");

        outbox.markPublished(1, Instant.parse("2026-10-23T10:00:01Z")).block();
        outbox.markFailed(2).block();
        assertThat(outbox.pending(10).map(OutboxEntry::seq).collectList().block()).containsExactly(2L);
        assertThat(outbox.after(0, 10).map(OutboxEntry::attempts).collectList().block()).containsExactly(0, 1);
        assertThat(outbox.after(1, 10).map(OutboxEntry::seq).collectList().block()).containsExactly(2L);
        assertThat(outbox.after(0, 1).blockFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-23T10:00:01Z"));
    }

    @Test
    void theLeaseHasOneOwnerUntilItExpires() {
        Instant now = Instant.parse("2026-10-23T10:00:00Z");
        assertThat(outbox.acquireLease("a", Duration.ofSeconds(30), now).block()).isTrue();
        assertThat(outbox.acquireLease("b", Duration.ofSeconds(30), now.plusSeconds(10)).block()).isFalse();
        assertThat(outbox.acquireLease("a", Duration.ofSeconds(30), now.plusSeconds(10)).block()).isTrue();
        assertThat(outbox.acquireLease("b", Duration.ofSeconds(30), now.plusSeconds(41)).block()).isTrue();
        outbox.releaseLease("b").block();
        assertThat(outbox.acquireLease("a", Duration.ofSeconds(30), now.plusSeconds(42)).block()).isTrue();
    }

    @Test
    void theRelayPublishesFromMongo() {
        InMemoryEventPublisher publisher = new InMemoryEventPublisher();
        OutboxRelay relay = new OutboxRelay(outbox, publisher, java.time.Clock.systemUTC(), "relay-test", Duration.ofSeconds(30), 100);
        outbox.append("console.jobs", "job-2", InMemoryOutboxTest.event("relay:1:1")).block();
        assertThat(relay.tick().block()).isGreaterThanOrEqualTo(1L);
        assertThat(publisher.published()).extracting(OutboxEntry::key).contains("relay:1:1");
        relay.stop();
    }
}

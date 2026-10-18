package com.iaf.platform.common.adapters.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class JdbcTest {

    @RegisterExtension
    final TestDatabase db = TestDatabase.h2();

    @Test
    void upsertInsertsTheFirstTimeAndUpdatesAfter() {
        Jdbc jdbc = db.jdbc();
        String update = "UPDATE store_seeds SET seeded_at = ? WHERE name = ?";
        String insert = "INSERT INTO store_seeds (name, seeded_at) VALUES (?, ?) ON CONFLICT DO NOTHING";
        Instant first = Instant.parse("2026-10-18T10:00:00Z");
        Instant second = first.plusSeconds(60);
        jdbc.upsert(update, new Object[] {Jdbc.time(first), "a"}, insert, new Object[] {"a", Jdbc.time(first)});
        jdbc.upsert(update, new Object[] {Jdbc.time(second), "a"}, insert, new Object[] {"a", Jdbc.time(second)});
        assertThat(jdbc.seededAt("a")).contains(second);
        assertThat(jdbc.query("SELECT COUNT(*) FROM store_seeds", (rs, i) -> rs.getLong(1))).containsExactly(1L);
    }

    @Test
    void claimsASeedOnlyOnce() {
        Instant at = Instant.parse("2026-10-18T10:00:00.123456789Z");
        assertThat(db.jdbc().claimSeed("demo-history", at)).isTrue();
        assertThat(db.jdbc().claimSeed("demo-history", at.plusSeconds(5))).isFalse();
        assertThat(db.jdbc().seededAt("demo-history")).contains(Instant.parse("2026-10-18T10:00:00.123456Z"));
    }

    @Test
    void movesAStoreRevisionOnEveryBump() {
        Jdbc jdbc = db.jdbc();
        jdbc.bump("workflows");
        jdbc.bump("workflows");
        jdbc.bump("something-new");
        assertThat(jdbc.revision("workflows")).isEqualTo(2);
        assertThat(jdbc.revision("something-new")).isEqualTo(1);
        assertThat(jdbc.revision("never-written")).isZero();
    }

    @Test
    void revisionedReloadsOnlyAfterAWrite() {
        AtomicInteger loads = new AtomicInteger();
        Revisioned<Integer> table = new Revisioned<>(db.jdbc(), "providers", loads::incrementAndGet);
        assertThat(table.get()).isEqualTo(1);
        assertThat(table.get()).isEqualTo(1);
        table.bump();
        assertThat(table.get()).isEqualTo(2);
        assertThat(loads).hasValue(2);
    }

    @Test
    void keepsInstantsToTheNanosecondInJsonAndAsEpochNanos() {
        Instant at = Instant.parse("2026-10-18T10:00:00.123456789Z");
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("at", at.toString()).put("n", 3).putNull("none");
        record Sample(Instant at, JsonNode data) {
        }
        Sample back = db.jdbc().read(db.jdbc().json(new Sample(at, data)), Sample.class);
        assertThat(back.at()).isEqualTo(at);
        assertThat(back.data()).isEqualTo(data);
        assertThat(Jdbc.nanos(at)).isEqualTo(at.getEpochSecond() * 1_000_000_000L + 123_456_789L);
        assertThat(Jdbc.time(at).toInstant()).isEqualTo(Instant.parse("2026-10-18T10:00:00.123456Z"));
    }

    @Test
    void countsStatementsThatRunOnANonBlockingThread() {
        Jdbc jdbc = db.jdbc();
        jdbc.revision("workflows");
        assertThat(jdbc.callsOnNonBlockingThreads()).isZero();
        Mono.fromCallable(() -> jdbc.revision("workflows")).subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5));
        assertThat(jdbc.callsOnNonBlockingThreads()).isEqualTo(1);
        Mono.fromCallable(() -> jdbc.revision("workflows")).subscribeOn(Schedulers.boundedElastic()).block(Duration.ofSeconds(5));
        assertThat(jdbc.callsOnNonBlockingThreads()).isEqualTo(1);
    }
}

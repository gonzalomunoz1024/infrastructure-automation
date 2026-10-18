package com.iaf.platform.common.adapters.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.config.PlatformProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The contract runs on its fixed clock, a day or more behind the server's, so its stores leave
 * the TTL index out (its monitor would judge their samples by the server's clock); the TTL test
 * runs on the system clock.
 */
@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoTelemetryTest extends TelemetryContract {

    @RegisterExtension
    final TestMongo db = new TestMongo();

    final List<MongoTelemetry> made = new ArrayList<>();

    MongoTelemetry telemetry(Mongo mongo, MeterRegistry registry, Duration retention) {
        return kept(new MongoTelemetry(mongo, registry, CLOCK, retention));
    }

    MongoTelemetry kept(MongoTelemetry t) {
        made.add(t);
        return t;
    }

    @AfterEach
    void stopWriters() {
        made.forEach(MongoTelemetry::close);
    }

    @Override
    TelemetryPort newStore(MeterRegistry registry) {
        return telemetry(db.mongo(), registry, Duration.ofDays(30));
    }

    @Override
    TelemetryPort reopen() {
        return telemetry(new Mongo(db.database()), new SimpleMeterRegistry(), Duration.ofDays(30));
    }

    @Test
    void theBackgroundWriterStoresWhatWasRecorded() throws Exception {
        store().record(sample(NOW, "vsphere", true, 7, null));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (count() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void deletesSamplesOlderThanTheConfiguredRetention() {
        MongoTelemetry telemetry = telemetry(db.mongo(), new SimpleMeterRegistry(), Duration.ofDays(7));
        telemetry.record(sample(NOW.minus(Duration.ofDays(8)), "vsphere", true, 1, null));
        telemetry.record(sample(NOW.minus(Duration.ofDays(6)), "vsphere", true, 2, null));
        assertThat(telemetry.samples(null, null)).extracting(CallSample::durationMs).containsExactly(2L);
        assertThat(count()).isEqualTo(2);

        assertThat(telemetry.purge()).isEqualTo(1);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void writesManySamplesInBatches() {
        MongoTelemetry telemetry = (MongoTelemetry) store();
        for (int i = 0; i < MongoTelemetry.BATCH * 2 + 7; i++) {
            telemetry.record(sample(NOW.minusMillis(i), "vsphere", true, i, null));
        }
        telemetry.flush();
        assertThat(count()).isEqualTo(MongoTelemetry.BATCH * 2 + 7);
        assertThat(telemetry.samples("vsphere", null)).hasSize(MongoTelemetry.BATCH * 2 + 7);
    }

    @Test
    void mongoDbDeletesExpiredSamplesTooAndFollowsAChangedRetention() {
        PlatformProperties props = new PlatformProperties();
        props.getPersistence().setTelemetryRetention(Duration.ofDays(7));
        MongoTelemetry week = kept(new MongoTelemetry(db.mongo(), TestMongo.client(), new SimpleMeterRegistry(), Clock.systemUTC(), props));
        assertThat(ttlIndexes()).singleElement().satisfies(ix -> assertThat(expireAfterSeconds(ix)).isEqualTo(Duration.ofDays(7).toSeconds()));

        Instant at = Instant.now();
        week.record(sample(at, "vsphere", true, 1, null));
        week.flush();
        Document stored = db.mongo().collection(MongoTelemetry.COLLECTION).find().first();
        assertThat(stored.get("at")).as("the date the TTL index reads").isEqualTo(Date.from(at));
        assertThat(stored.getLong("atNs")).isEqualTo(Mongo.nanos(at));

        // the next start with another retention changes the index in place; one with the same changes nothing
        kept(new MongoTelemetry(db.mongo(), new SimpleMeterRegistry(), Clock.systemUTC(), Duration.ofDays(30), db.database()));
        kept(new MongoTelemetry(db.mongo(), new SimpleMeterRegistry(), Clock.systemUTC(), Duration.ofDays(30), db.database()));
        assertThat(ttlIndexes()).singleElement().satisfies(ix -> assertThat(expireAfterSeconds(ix)).isEqualTo(Duration.ofDays(30).toSeconds()));

        kept(new MongoTelemetry(db.mongo(), new SimpleMeterRegistry(), Clock.systemUTC(), Duration.ofDays(365L * 100), db.database()));
        assertThat(ttlIndexes()).singleElement().satisfies(ix -> assertThat(expireAfterSeconds(ix)).as("capped at what MongoDB takes")
                .isEqualTo(Integer.MAX_VALUE));
        assertThat(week.samples("vsphere", null)).hasSize(1);
    }

    @Test
    void aStoreOnAClockOfItsOwnMakesNoTtlIndex() {
        store();
        assertThat(ttlIndexes()).isEmpty();
    }

    private List<Document> ttlIndexes() {
        List<Document> out = new ArrayList<>();
        db.mongo().collection(MongoTelemetry.COLLECTION).listIndexes().forEach(ix -> {
            if (ix.containsKey("expireAfterSeconds")) {
                out.add(ix);
            }
        });
        return out;
    }

    private static long expireAfterSeconds(Document index) {
        assertThat(index.get("key", Document.class)).isEqualTo(new Document("at", 1));
        return ((Number) index.get("expireAfterSeconds")).longValue();
    }

    private long count() {
        return db.mongo().collection(MongoTelemetry.COLLECTION).countDocuments();
    }
}

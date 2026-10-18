package com.iaf.platform.common.adapters.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class RevisionedTest {

    static final String STORE = "things";

    @RegisterExtension
    final TestMongo db = new TestMongo();

    final AtomicInteger loads = new AtomicInteger();
    long now;

    /** How many things the collection holds, as a copy kept 30 seconds at most. */
    Revisioned<Long> things() {
        return new Revisioned<>(db.mongo(), STORE, () -> {
            loads.incrementAndGet();
            return db.mongo().collection(STORE).countDocuments();
        }, Duration.ofSeconds(30), () -> now);
    }

    void add(String id) {
        db.mongo().collection(STORE).insertOne(new Document("_id", id));
    }

    @Test
    void aMovedRevisionReloadsAtOnceAndAStillOneServesTheCopy() {
        Revisioned<Long> things = things();
        assertThat(things.get()).isZero();
        add("a");
        things.bump();
        assertThat(things.get()).isEqualTo(1);
        assertThat(things.get()).isEqualTo(1);
        assertThat(loads).hasValue(2);
    }

    @Test
    void aWriteWhoseBumpWasLostIsSeenOnceTheCopyIsOld() {
        Revisioned<Long> things = things();
        assertThat(things.get()).isZero();
        add("a"); // the writer died before it moved the revision
        now += Duration.ofSeconds(29).toNanos();
        assertThat(things.get()).as("younger than the maximum age, the copy is served").isZero();
        now += Duration.ofSeconds(1).toNanos();
        assertThat(things.get()).as("as old as the maximum age, the copy is reloaded").isEqualTo(1);
        assertThat(db.mongo().revision(STORE)).isZero();
        assertThat(loads).hasValue(2);

        now += Duration.ofSeconds(10).toNanos();
        assertThat(things.get()).as("the age counts from the reload").isEqualTo(1);
        assertThat(loads).hasValue(2);
    }
}

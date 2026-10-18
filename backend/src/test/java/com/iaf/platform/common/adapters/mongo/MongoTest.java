package com.iaf.platform.common.adapters.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.config.PlatformProperties;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoTest {

    @RegisterExtension
    final TestMongo db = new TestMongo();

    record Shape(String name, Instant at, long big, double ratio, JsonNode schema, List<String> tags, Map<String, Integer> counts) {
    }

    @Test
    void aRecordComesBackExactlyAsStoredWithItsKeysAsTheyAre() throws Exception {
        // an imported OpenAPI schema keeps its $ref, and a key with a dot stays one key
        JsonNode schema = new ObjectMapper().readTree("""
                {"$ref": "#/components/schemas/Vm", "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "properties": {"metadata.name": {"type": "string"}, "cpu": {"type": "integer", "maximum": 64, "multipleOf": 0.5}}}
                """);
        Shape shape = new Shape("web-07", Instant.parse("2026-10-18T09:15:30.123456789Z"), 5_000_000_000L, 0.995, schema, List.of("a", "b"),
                Map.of("x", 1));
        db.mongo().collection("shapes").insertOne(new Document("_id", "one").append(Mongo.DOC, db.mongo().doc(shape)));

        Document stored = db.mongo().collection("shapes").find().first();
        assertThat(db.mongo().read(stored, Shape.class)).isEqualTo(shape);
        assertThat(stored.get(Mongo.DOC, Document.class).getString("at")).as("instants are kept to the nanosecond").isEqualTo("2026-10-18T09:15:30.123456789Z");
        assertThat(stored.get(Mongo.DOC, Document.class).get("schema", Document.class).getString("$ref")).isEqualTo("#/components/schemas/Vm");
    }

    @Test
    void aSchemaPartWithIdBesideRefStaysAPartAtEveryDepthThroughEveryWrite() throws Exception {
        // the driver's own codecs read a document holding both $ref and $id as a DBRef and drop its other keys
        JsonNode schema = new ObjectMapper().readTree("""
                {"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://schemas.example/vm.json", "$ref": "#/$defs/Vm",
                 "$defs": {"Vm": {"$id": "vm", "$ref": "#/$defs/Base", "properties": {"metadata.name": {"$id": "name", "$ref": "#/$defs/Name"}}}},
                 "allOf": [{"$ref": "#/$defs/Base", "$id": "base", "title": "Base"}, [{"$ref": "#/$defs/Tag", "$id": "tag"}]]}
                """);
        Shape shape = new Shape("web-07", Instant.parse("2026-10-18T09:15:30.123456789Z"), 1L, 0.5, schema, List.of(), Map.of());
        MongoCollection<Document> shapes = db.mongo().collection("shapes");
        shapes.insertOne(new Document("_id", "inserted").append(Mongo.DOC, db.mongo().doc(shape)));
        shapes.replaceOne(Filters.eq("_id", "replaced"), new Document("_id", "replaced").append(Mongo.DOC, db.mongo().doc(shape)),
                new ReplaceOptions().upsert(true));
        shapes.updateOne(Filters.eq("_id", "set"), Updates.set(Mongo.DOC, db.mongo().doc(shape)), new UpdateOptions().upsert(true));

        for (String id : List.of("inserted", "replaced", "set")) {
            assertThat(db.mongo().read(shapes.find(Filters.eq("_id", id)).first(), Shape.class)).as(id).isEqualTo(shape);
        }
    }

    @Test
    void aStoreRevisionMovesOnEveryBump() {
        assertThat(db.mongo().revision("workflows")).isZero();
        db.mongo().bump("workflows");
        db.mongo().bump("workflows");
        assertThat(db.mongo().revision("workflows")).isEqualTo(2);
        assertThat(db.mongo().revision("providers")).isZero();
    }

    @Test
    void aSeedIsClaimedOncePerDatabase() {
        Instant at = Instant.parse("2026-10-18T09:15:30.123456789Z");
        assertThat(db.mongo().claimSeed("demo-history", at)).isTrue();
        assertThat(db.mongo().claimSeed("demo-history", at.plusSeconds(60))).isFalse();
        assertThat(db.mongo().seededAt("demo-history")).contains(at);
        assertThat(db.mongo().seededAt("other")).isEmpty();
    }

    @Test
    void theDemoHistoryIsSeededOnlyByTheFirstStart() {
        PlatformProperties first = new PlatformProperties();
        first.getDemo().setSeedHistory(true);
        MongoConfig.demoHistoryOnce(db.mongo(), first, java.time.Clock.systemUTC());
        assertThat(first.getDemo().isSeedHistory()).isTrue();
        PlatformProperties second = new PlatformProperties();
        second.getDemo().setSeedHistory(true);
        MongoConfig.demoHistoryOnce(db.mongo(), second, java.time.Clock.systemUTC());
        assertThat(second.getDemo().isSeedHistory()).as("a restart on the same database seeds nothing").isFalse();
    }

    @Test
    void aCallOnAnEventLoopThreadIsCounted() {
        Mongo mongo = db.mongo();
        Mono.fromRunnable(() -> mongo.collection("shapes").countDocuments()).subscribeOn(Schedulers.parallel()).block();
        assertThat(mongo.callsOnNonBlockingThreads()).isEqualTo(1);
        Mono.fromRunnable(() -> mongo.collection("shapes").countDocuments()).subscribeOn(Schedulers.boundedElastic()).block();
        assertThat(mongo.callsOnNonBlockingThreads()).isEqualTo(1);
    }
}

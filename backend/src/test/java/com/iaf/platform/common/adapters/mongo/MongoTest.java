package com.iaf.platform.common.adapters.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.BsonType;
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
    void anIntegerBeyondALongComesBackAsThatInteger() throws Exception {
        // an OpenAPI uint64 bound, which the driver alone cannot write; a long stays a long
        JsonNode schema = new ObjectMapper().readTree("""
                {"type": "integer", "format": "uint64", "minimum": 0, "maximum": 18446744073709551615,
                 "examples": [9223372036854775807, -18446744073709551616, 123456789012345678901234567890123, {"limit": 18446744073709551615}]}
                """);
        Shape shape = new Shape("web-07", Instant.parse("2026-10-18T09:15:30.123456789Z"), Long.MAX_VALUE, 0.5, schema, List.of(), Map.of());
        MongoCollection<Document> shapes = db.mongo().collection("shapes");
        shapes.insertOne(new Document("_id", "inserted").append(Mongo.DOC, db.mongo().doc(shape)));
        shapes.replaceOne(Filters.eq("_id", "replaced"), new Document("_id", "replaced").append(Mongo.DOC, db.mongo().doc(shape)),
                new ReplaceOptions().upsert(true));

        for (String id : List.of("inserted", "replaced")) {
            Shape read = db.mongo().read(shapes.find(Filters.eq("_id", id)).first(), Shape.class);
            assertThat(read).as(id).isEqualTo(shape);
            assertThat(read.schema().get("maximum").bigIntegerValue()).isEqualTo(new BigInteger("18446744073709551615"));
        }
        assertThat(shapes.countDocuments(Filters.type("doc.schema.maximum", BsonType.DECIMAL128))).as("up to 34 digits: a Decimal128").isEqualTo(2);
    }

    @Test
    void anIntegerTooLongForADecimal128ComesBackExactlyThroughEveryWrite() throws Exception {
        // a run's input or an HTTP step's response can hold any integer: 35 and 40 digits, either sign, at depth and in arrays
        String d35 = "12345678901234567890123456789012345";
        String d40 = "9876543210987654321098765432109876543210";
        JsonNode schema = new ObjectMapper().readTree("""
                {"positive": %1$s, "negative": -%1$s, "longer": %2$s, "lower": -%2$s, "fits": 1234567890123456789012345678901234,
                 "deep": {"inner": {"value": -%2$s, "list": [%1$s, -%2$s, {"x": -%1$s}, [%2$s, 7]]}},
                 "list": [-%1$s, %2$s, 1, 99999999999999999999]}
                """.formatted(d35, d40));
        Shape shape = new Shape("web-07", Instant.parse("2026-10-18T09:15:30.123456789Z"), Long.MIN_VALUE, 0.5, schema, List.of(), Map.of());
        MongoCollection<Document> shapes = db.mongo().collection("shapes");
        shapes.insertOne(new Document("_id", "inserted").append(Mongo.DOC, db.mongo().doc(shape)));
        shapes.replaceOne(Filters.eq("_id", "replaced"), new Document("_id", "replaced").append(Mongo.DOC, db.mongo().doc(shape)),
                new ReplaceOptions().upsert(true));
        shapes.updateOne(Filters.eq("_id", "set"), Updates.set(Mongo.DOC, db.mongo().doc(shape)), new UpdateOptions().upsert(true));

        BigInteger p35 = new BigInteger(d35);
        BigInteger p40 = new BigInteger(d40);
        for (String id : List.of("inserted", "replaced", "set")) {
            Document stored = shapes.find(Filters.eq("_id", id)).first();
            Shape read = db.mongo().read(stored, Shape.class);
            assertThat(read).as(id).isEqualTo(shape);
            assertThat(read.schema().get("negative").bigIntegerValue()).as(id).isEqualTo(p35.negate());
            assertThat(read.schema().at("/deep/inner/list/3/0").isBigInteger()).as(id).isTrue();
            assertThat(read.schema().at("/deep/inner/list/3/0").bigIntegerValue()).as(id).isEqualTo(p40);
            // the stored document holds the same Java type as a shorter one: a BigInteger
            Document storedSchema = stored.get(Mongo.DOC, Document.class).get("schema", Document.class);
            assertThat(storedSchema.get("longer")).as(id).isEqualTo(p40);
            assertThat(storedSchema.get("lower")).as(id).isEqualTo(p40.negate());
            assertThat(storedSchema.get("fits")).as(id).isEqualTo(new BigInteger("1234567890123456789012345678901234"));
            assertThat(storedSchema.getList("list", Object.class)).as(id).containsExactly(p35.negate(), p40, 1, new BigInteger("99999999999999999999"));
            assertThat(storedSchema.get("deep", Document.class).get("inner", Document.class).getList("list", Object.class).get(2))
                    .as(id).isEqualTo(new Document("x", p35.negate()));
        }
        assertThat(shapes.countDocuments(Filters.type("doc.schema.fits", BsonType.DECIMAL128))).as("34 digits stay a Decimal128").isEqualTo(3);
        // decoded as a BigInteger on its own too, not only inside a document
        assertThat(shapes.distinct("doc.schema.lower", BigInteger.class).into(new ArrayList<>())).containsExactly(p40.negate());
        assertThat(shapes.distinct("doc.schema.fits", BigInteger.class).into(new ArrayList<>()))
                .containsExactly(new BigInteger("1234567890123456789012345678901234"));
    }

    record Note(String title, String owner, JsonNode data, Map<String, Object> labels) {
    }

    @Test
    void aNullInsideAJsonValueOrAMapComesBackAndANullComponentIsLeftOut() throws Exception {
        // a job's input, or an evidence entry's data, whose hash is checked again on every read
        JsonNode data = new ObjectMapper().readTree("""
                {"a": 1, "gone": null, "inner": {"x": null, "list": [null, {"y": null}]}}
                """);
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("team", "platform");
        labels.put("unset", null);
        labels.put("nested", new LinkedHashMap<>(Collections.singletonMap("z", null)));
        Note note = new Note("web-07", null, data, labels);
        MongoCollection<Document> notes = db.mongo().collection("notes");
        notes.insertOne(new Document("_id", "one").append(Mongo.DOC, db.mongo().doc(note)));

        Document stored = notes.find().first();
        assertThat(db.mongo().read(stored, Note.class)).isEqualTo(note);
        assertThat(stored.get(Mongo.DOC, Document.class)).as("a null component is left out").doesNotContainKey("owner");
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

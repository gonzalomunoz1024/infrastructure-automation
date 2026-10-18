package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.mongodb.client.model.Filters;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoProviderStoreTest extends ProviderStoreContract {

    /** A provider whose schemas came from an OpenAPI import: $schema, $id beside $ref, dotted keys, a uint64 bound, nulls. */
    static final String FLEET = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: fleet, title: Fleet, owner: team-a, version: 2.0.0 }
            spec:
              type: http
              endpoints:
                prod: https://fleet.example
              actions:
                - id: vm.create
                  title: Create a VM
                  operation: { method: POST, path: /vms }
                  effect: create
                  input:
                    $schema: https://json-schema.org/draft/2020-12/schema
                    $id: https://fleet.example/schemas/vm-request.json
                    $ref: "#/$defs/VmRequest"
                    $defs:
                      VmRequest:
                        $id: vm-request
                        $ref: "#/$defs/Base"
                        type: object
                        properties:
                          metadata.name: { type: string, default: null, enum: [web, null] }
                          "disk.size": { type: integer, format: uint64, minimum: 0, maximum: 18446744073709551615 }
                        allOf:
                          - { $ref: "#/$defs/Labels", $id: labels }
                      Base: { type: object }
                      Labels: { type: object, additionalProperties: { type: string } }
                  output:
                    $ref: "#/components/schemas/Vm"
                    components.schemas: { Vm: { $id: vm, $ref: "#/$defs/Vm" } }
                  sample: { id: vm-1, "power.state": poweredOn, $ref: not-a-reference, owner: null }
              overrides:
                vm.create: { response: { $id: override, $ref: "#/components/schemas/Vm" } }
            """;

    @RegisterExtension
    final TestMongo db = new TestMongo();

    MongoProviderStore store(PlatformProperties props, Mongo mongo) {
        MongoProviderStore store = new MongoProviderStore(mongo, props, Clock.fixed(NOW, ZoneOffset.UTC));
        store.init();
        return store;
    }

    @Override
    ProviderStorePort newStore(PlatformProperties props) {
        return store(props, db.mongo());
    }

    @Override
    ProviderStorePort reopen(PlatformProperties props) {
        return store(props, new Mongo(db.database()));
    }

    @Test
    void seedingAgainAddsNothing() throws Exception {
        write(ECHO);
        newStore(props(content, false));
        assertThat(((MongoProviderStore) reopen(props(content, false))).seed()).isZero();
    }

    @Test
    void aProviderStillAsSeededTakesItsChangedFile() throws Exception {
        write(ECHO);
        newStore(props(content, false));
        write(ECHO.replace("https://echo.example", "https://moved.example"));
        StoredProvider refreshed = reopen(props(content, false)).get("echo").orElseThrow();
        assertThat(refreshed.yaml()).contains("https://moved.example");
        assertThat(refreshed.updatedBy()).isEqualTo("content");
    }

    @Test
    void anotherInstanceSeesAWriteAtOnce() throws Exception {
        write(ECHO);
        ProviderStorePort store = newStore(props(content, false));
        ProviderStorePort other = reopen(props(content, false));
        assertThat(other.get("echo")).isPresent();
        store.delete("echo");
        assertThat(other.get("echo")).isEmpty();
    }

    @Test
    void anotherInstanceSeesAKeptVersionAtOnce() throws Exception {
        write(ECHO);
        ProviderStorePort store = newStore(props(content, false));
        ProviderStorePort other = reopen(props(content, false));
        assertThat(other.versions("echo")).hasSize(1);
        StoredProvider candidate = echo("1.1.0", "ana");
        store.saveVersion(candidate);
        assertThat(other.version("echo", "1.1.0")).contains(candidate);
        assertThat(other.get("echo").map(StoredProvider::updatedBy)).contains("content");
    }

    @Test
    void aCurrentVersionWithoutItsOwnDocumentStillCountsAmongTheVersions() throws Exception {
        // what a save that stopped between the provider's document and its version's leaves behind
        write(ECHO);
        ProviderStorePort store = newStore(props(content, false));
        db.mongo().collection(MongoProviderStore.VERSIONS).deleteMany(new Document());
        db.mongo().bump(MongoProviderStore.STORE);
        StoredProvider current = store.get("echo").orElseThrow();
        assertThat(store.versions("echo")).containsExactly(current);
        assertThat(store.version("echo", "1.0.0")).contains(current);

        store.saveVersion(echo("1.1.0", "ana"));
        assertThat(versionsOf(store.versions("echo"))).containsExactly("1.1.0", "1.0.0");
        assertThat(store.version("echo", "1.0.0")).contains(current);
    }

    @Test
    void aDeleteThatStoppedAfterTheVersionsIsFinishedByDeletingAgain() throws Exception {
        write(ECHO);
        ProviderStorePort store = newStore(props(content, false));
        store.saveVersion(echo("1.1.0", "ana"));
        db.mongo().collection(MongoProviderStore.VERSIONS).deleteMany(Filters.eq("name", "echo"));
        db.mongo().bump(MongoProviderStore.STORE);
        StoredProvider current = store.get("echo").orElseThrow();
        assertThat(store.versions("echo")).as("the provider is left with its current version alone").containsExactly(current);

        store.delete("echo");
        assertThat(store.get("echo")).isEmpty();
        assertThat(store.versions("echo")).isEmpty();
        assertThat(db.mongo().collection(MongoProviderStore.PROVIDERS).countDocuments()).isZero();
    }

    @Test
    void keepsOneDocumentPerProviderAndOnePerKeptVersion() throws Exception {
        write(ECHO);
        ProviderStorePort store = newStore(props(content, false));
        store.saveVersion(echo("1.1.0", "ana"));

        assertThat(ids(MongoProviderStore.PROVIDERS)).containsExactly("echo");
        assertThat(ids(MongoProviderStore.VERSIONS)).containsExactlyInAnyOrder("echo@1.0.0", "echo@1.1.0");
        Document kept = db.mongo().collection(MongoProviderStore.VERSIONS).find(Filters.eq("_id", "echo@1.1.0")).first();
        assertThat(kept.getString("name")).isEqualTo("echo");
        assertThat(kept.getString("version")).isEqualTo("1.1.0");
        assertThat(kept.get("updatedAt")).isEqualTo(Date.from(NOW));
        assertThat(db.mongo().read(kept, StoredProvider.class)).isEqualTo(echo("1.1.0", "ana"));
        List<Document> indexes = db.mongo().collection(MongoProviderStore.VERSIONS).listIndexes().into(new ArrayList<>());
        assertThat(indexes).extracting(i -> i.get("key", Document.class)).contains(new Document("name", 1));
    }

    @Test
    void keepsAnImportedSchemaExactlyAsItWasWritten() throws Exception {
        Path file = content.resolve("providers/team-a/fleet.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, FLEET);
        ProviderStorePort store = newStore(props(content, false));
        assertThat(reopen(props(content, false)).get("fleet").orElseThrow().definition()).as("seeded").isEqualTo(ProviderYaml.parse(FLEET));

        String changedYaml = FLEET.replace("https://fleet.example", "https://fleet2.example");
        StoredProvider changed = new StoredProvider(ProviderYaml.parse(changedYaml), changedYaml, "providers/team-a/fleet.yaml", NOW, "ana");
        String candidateYaml = changedYaml.replace("version: 2.0.0", "version: 2.1.0");
        StoredProvider candidate = new StoredProvider(ProviderYaml.parse(candidateYaml), candidateYaml, "providers/team-a/fleet.yaml", NOW, "ana");
        store.save(changed);
        store.saveVersion(candidate);

        ProviderStorePort after = reopen(props(content, false));
        assertThat(after.get("fleet")).as("saved").contains(changed);
        assertThat(after.version("fleet", "2.1.0")).as("kept").contains(candidate);
        JsonNode input = after.get("fleet").orElseThrow().definition().action("vm.create").orElseThrow().input();
        assertThat(input.get("$ref").asText()).isEqualTo("#/$defs/VmRequest");
        assertThat(input.at("/$defs/VmRequest/$id").asText()).isEqualTo("vm-request");
        assertThat(input.at("/$defs/VmRequest/properties/metadata.name/default").isNull()).as("a null is kept").isTrue();
        assertThat(input.at("/$defs/VmRequest/properties/disk.size/maximum").bigIntegerValue()).isEqualTo(new BigInteger("18446744073709551615"));
    }

    private List<Object> ids(String collection) {
        return db.mongo().collection(collection).find().map(d -> d.get("_id")).into(new ArrayList<>());
    }
}

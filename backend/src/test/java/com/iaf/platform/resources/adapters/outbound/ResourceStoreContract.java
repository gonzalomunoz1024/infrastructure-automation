package com.iaf.platform.resources.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.outbound.ResourceStorePort;

/** What every {@link ResourceStorePort} does, in memory and in a database. */
abstract class ResourceStoreContract {

    static final Instant NOW = Instant.parse("2026-10-17T12:00:00.123456789Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** A store over {@code props}, seeded as at startup. */
    abstract ResourceStorePort newStore(PlatformProperties props);

    /** A new store over the same storage, seeded as after a restart; null when the storage does not outlive the store. */
    abstract ResourceStorePort reopen(PlatformProperties props);

    static PlatformProperties props(String dir, boolean demo) {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir(dir);
        props.getDemo().setSeedHistory(demo);
        return props;
    }

    ResourceStorePort store(boolean demo) {
        return newStore(props(SampleCatalog.ROOT.toString(), demo));
    }

    static Resource vm(String id, Instant createdAt) {
        ObjectNode attributes = JsonNodeFactory.instance.objectNode().put("name", "web-" + id).put("cpu", 4).put("ready", true);
        attributes.putArray("tags").add("prod").add("web");
        return new Resource(id, "vm", "web-" + id, "building", attributes, "team-compute", "team",
                new Resource.Origin("job_" + id, "create-vm", "2.0.1"), List.of("image-demo01"), createdAt, createdAt);
    }

    @Test
    void readsTheResourceTypesOfTheContentDir() {
        ResourceStorePort store = store(false);
        assertThat(store.types()).extracting(ResourceTypeDefinition::name).containsExactly("database", "environment", "image", "vm", "volume");
        assertThat(store.type("image").orElseThrow().plural()).isEqualTo("images");
        assertThat(store.type("image").orElseThrow().producedBy()).isEqualTo("build-vm-image");
        assertThat(store.type("image")).contains(SampleCatalog.type("image"));
        assertThat(store.type("nope")).isEmpty();
        assertThat(store.type(null)).isEmpty();
        assertThat(store.resources()).isEmpty();
    }

    @Test
    void seedsDemoResourcesWithLineageWhenAsked() {
        ResourceStorePort store = store(true);
        List<Resource> all = store.resources();
        assertThat(all).isNotEmpty();
        for (ResourceTypeDefinition t : store.types()) {
            assertThat(all).as(t.name()).anyMatch(r -> r.type().equals(t.name()));
        }
        Resource vm = all.stream().filter(r -> r.type().equals("vm")).findFirst().orElseThrow();
        Resource image = store.resource(vm.builtFrom().get(0)).orElseThrow();
        assertThat(image.type()).isEqualTo("image");
        assertThat(all).allMatch(r -> r.createdAt().isBefore(NOW));
    }

    @Test
    void readsAResourceBackExactly() {
        ResourceStorePort store = store(false);
        Resource saved = vm("vm-abc123", NOW);
        store.save(saved);
        assertThat(store.resource("vm-abc123")).contains(saved);
        assertThat(store.resource("vm-unknown")).isEmpty();
        assertThat(store.resource(null)).isEmpty();
    }

    @Test
    void savesAndOverwritesById() {
        ResourceStorePort store = store(false);
        Resource r = vm("vm-abc123", NOW);
        store.save(r);
        store.save(r.withStatus("ready", NOW.plusSeconds(5)));
        assertThat(store.resources()).hasSize(1);
        assertThat(store.resource("vm-abc123").orElseThrow().status()).isEqualTo("ready");
    }

    @Test
    void listsResourcesByTypeThenOldestFirstThenId() {
        ResourceStorePort store = store(false);
        store.save(vm("vm-b", NOW));
        store.save(vm("vm-a", NOW));
        store.save(vm("vm-old", NOW.minusNanos(1)));
        store.save(vm("vm-undated", null));
        Resource db = new Resource("db-1", "database", "orders", "ready", null, "team-data", "team", null, List.of(), NOW.plusSeconds(60), null);
        store.save(db);
        assertThat(store.resources()).extracting(Resource::id).containsExactly("db-1", "vm-old", "vm-a", "vm-b", "vm-undated");
    }

    @Test
    void listsASavedTypeByName() {
        ResourceStorePort store = store(false);
        ResourceTypeDefinition bucket = new ResourceTypeDefinition("bucket", "buckets", "team-storage", null, List.of("ready", "retired"), null, "Object storage");
        store.saveType(bucket);
        assertThat(store.types()).extracting(ResourceTypeDefinition::name).containsExactly("bucket", "database", "environment", "image", "vm", "volume");
        assertThat(store.type("bucket")).contains(bucket);
    }

    @Test
    void aMissingContentDirIsEmptyNotAnError() {
        ResourceStorePort store = newStore(props("/nowhere/at/all", true));
        assertThat(store.types()).isEmpty();
        assertThat(store.resources()).isEmpty();
    }

    @Test
    void keepsWhatItWroteAcrossARestart() {
        ResourceStorePort store = store(true);
        Resource saved = vm("vm-abc123", NOW);
        store.save(saved);
        int count = store.resources().size();
        ResourceStorePort after = reopen(props(SampleCatalog.ROOT.toString(), true));
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.resource("vm-abc123")).contains(saved);
        assertThat(after.resources()).as("the demo resources are not added twice").hasSize(count);
        assertThat(after.types()).hasSize(5);
    }
}

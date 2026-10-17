package com.iaf.platform.resources.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;

class InMemoryResourceStoreTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);

    static InMemoryResourceStore store(boolean demo) {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir(SampleCatalog.ROOT.toString());
        props.getDemo().setSeedHistory(demo);
        InMemoryResourceStore store = new InMemoryResourceStore(props, CLOCK);
        store.seed();
        return store;
    }

    @Test
    void readsTheResourceTypesOfTheContentDir() {
        InMemoryResourceStore store = store(false);
        assertThat(store.types()).extracting(ResourceTypeDefinition::name).containsExactly("database", "environment", "image", "vm", "volume");
        assertThat(store.type("image")).isPresent();
        assertThat(store.type("image").orElseThrow().plural()).isEqualTo("images");
        assertThat(store.type("image").orElseThrow().producedBy()).isEqualTo("build-vm-image");
        assertThat(store.type("nope")).isEmpty();
        assertThat(store.resources()).isEmpty();
    }

    @Test
    void seedsDemoResourcesWithLineageWhenAsked() {
        InMemoryResourceStore store = store(true);
        List<Resource> all = store.resources();
        assertThat(all).isNotEmpty();
        for (ResourceTypeDefinition t : store.types()) {
            assertThat(all).as(t.name()).anyMatch(r -> r.type().equals(t.name()));
        }
        Resource vm = all.stream().filter(r -> r.type().equals("vm")).findFirst().orElseThrow();
        assertThat(vm.builtFrom()).hasSize(1);
        Resource image = store.resource(vm.builtFrom().get(0)).orElseThrow();
        assertThat(image.type()).isEqualTo("image");
        assertThat(image.status()).isEqualTo("ready");
        assertThat(all).anyMatch(r -> r.type().equals("image") && r.status().equals("deprecated"));
        Resource env = all.stream().filter(r -> r.type().equals("environment")).findFirst().orElseThrow();
        assertThat(env.builtFrom().stream().map(id -> store.resource(id).orElseThrow().type())).contains("vm", "database");
        assertThat(all).allMatch(r -> r.createdAt().isBefore(CLOCK.instant()));
    }

    @Test
    void savesAndOverwritesById() {
        InMemoryResourceStore store = store(false);
        Resource r = new Resource("vm-abc123", "vm", "web", "building", null, "team-compute", "team", null, List.of(), CLOCK.instant(), CLOCK.instant());
        store.save(r);
        store.save(r.withStatus("ready", CLOCK.instant()));
        assertThat(store.resources()).hasSize(1);
        assertThat(store.resource("vm-abc123").orElseThrow().status()).isEqualTo("ready");
    }

    @Test
    void aMissingContentDirIsEmptyNotAnError() {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir("/nowhere/at/all");
        InMemoryResourceStore store = new InMemoryResourceStore(props, CLOCK);
        store.seed();
        assertThat(store.types()).isEmpty();
        assertThat(store.resources()).isEmpty();
    }
}

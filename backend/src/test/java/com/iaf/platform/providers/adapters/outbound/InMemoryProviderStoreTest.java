package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;

class InMemoryProviderStoreTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String YAML = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: fleet, title: Fleet API, owner: team-compute, version: 1.0.0 }
            spec:
              type: http
              endpoints: { dev: https://fleet-dev.example.com }
              auth: { type: none }
              actions:
                - { id: vm.get, method: GET, path: "/vms/{id}", effect: read, retry: safe }
            """;

    @TempDir
    Path dir;

    InMemoryProviderStore store(boolean git) {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir(dir.toString());
        props.getContent().setGit(git);
        return new InMemoryProviderStore(props, CLOCK);
    }

    static StoredProvider version(String v) {
        String yaml = YAML.replace("version: 1.0.0", "version: " + v);
        return new StoredProvider(ProviderYaml.parse(yaml), yaml, "providers/team-compute/fleet.yaml", NOW, "mateo");
    }

    @Test
    void aCanaryCandidateIsKeptBesideTheCurrentVersionWithoutReplacingIt() throws IOException {
        InMemoryProviderStore store = store(true);
        store.save(version("1.0.0"));
        store.saveVersion(version("1.1.0"));

        assertThat(store.get("fleet").orElseThrow().definition().metadata().version()).isEqualTo("1.0.0");
        assertThat(store.all()).hasSize(1);
        assertThat(store.version("fleet", "1.1.0")).isPresent();
        assertThat(store.version("fleet", "1.0.0")).isPresent();
        assertThat(store.version("fleet", "2.0.0")).isEmpty();
        assertThat(store.versions("fleet")).extracting(p -> p.definition().metadata().version()).containsExactly("1.1.0", "1.0.0");
        assertThat(Files.readString(dir.resolve("providers/team-compute/fleet.yaml"))).as("the file stays the current version").contains("version: 1.0.0");

        store.save(store.version("fleet", "1.1.0").orElseThrow());
        assertThat(store.get("fleet").orElseThrow().definition().metadata().version()).isEqualTo("1.1.0");
        assertThat(Files.readString(dir.resolve("providers/team-compute/fleet.yaml"))).contains("version: 1.1.0");
    }

    @Test
    void deletingAProviderForgetsEveryVersion() {
        InMemoryProviderStore store = store(false);
        store.save(version("1.0.0"));
        store.saveVersion(version("1.1.0"));
        store.delete("fleet");
        assertThat(store.get("fleet")).isEmpty();
        assertThat(store.versions("fleet")).isEmpty();
        assertThat(store.version("fleet", "1.1.0")).isEmpty();
    }
}

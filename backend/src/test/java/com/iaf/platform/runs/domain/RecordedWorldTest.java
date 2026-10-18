package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;

/** What the recorded world remembers, and how a fork keeps its own memory on top of it. */
class RecordedWorldTest {

    final ProviderDefinition mongodb = SampleContent.provider("mongodb");
    final Action get = mongodb.action("cluster.get").orElseThrow();
    final Action upgrade = mongodb.action("cluster.upgrade").orElseThrow();
    final Action delete = mongodb.action("cluster.delete").orElseThrow();
    final Action create = mongodb.action("cluster.create").orElseThrow();

    static JsonNode input(Object... kv) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return SampleContent.json(m);
    }

    String versionOf(RecordedWorld w, String id) {
        return w.call(mongodb, get, input("id", id), "job_1", 1, true).path("version").asText();
    }

    @Test
    void aChangeIsReadBackAndADeleteMakesLaterReadsNotFound() {
        RecordedWorld world = new RecordedWorld();
        assertThat(versionOf(world, "db-1")).isEqualTo("7.0");
        world.call(mongodb, upgrade, input("id", "db-1", "version", "8.0"), "job_1", 2, true);
        assertThat(versionOf(world, "db-1")).isEqualTo("8.0");
        assertThat(versionOf(world, "db-2")).as("another id is untouched").isEqualTo("7.0");
        world.call(mongodb, delete, input("id", "db-1"), "job_1", 3, true);
        assertThatThrownBy(() -> versionOf(world, "db-1")).isInstanceOf(ProviderCallException.class)
                .extracting(e -> ((ProviderCallException) e).errorClass()).isEqualTo("not_found");
        assertThat(world.isDeleted("mongodb", "db-1")).isTrue();
    }

    @Test
    void aCallThatDoesNotRememberLeavesTheWorldAsItWas() {
        RecordedWorld world = new RecordedWorld();
        world.call(mongodb, upgrade, input("id", "db-1", "version", "8.0"), "job_1", 1, false);
        assertThat(versionOf(world, "db-1")).isEqualTo("7.0");
    }

    @Test
    void aForkReadsWhatTheWorldRemembersAndKeepsItsOwnChangesPrivate() {
        RecordedWorld world = new RecordedWorld();
        world.call(mongodb, upgrade, input("id", "db-1", "version", "8.0"), "job_1", 1, true);
        world.call(mongodb, delete, input("id", "db-3"), "job_1", 2, true);

        RecordedWorld fork = world.fork();
        assertThat(versionOf(fork, "db-1")).as("a fork sees the upgrade below it").isEqualTo("8.0");
        assertThat(fork.isDeleted("mongodb", "db-3")).as("and the delete below it").isTrue();

        // the fork's own upgrade and delete
        fork.call(mongodb, upgrade, input("id", "db-2", "version", "8.0"), "dry_1", 3, true);
        fork.call(mongodb, delete, input("id", "db-1"), "dry_1", 4, true);
        assertThat(versionOf(fork, "db-2")).isEqualTo("8.0");
        assertThat(fork.isDeleted("mongodb", "db-1")).isTrue();
        assertThat(versionOf(world, "db-2")).as("the world never saw the fork's upgrade").isEqualTo("7.0");
        assertThat(world.isDeleted("mongodb", "db-1")).as("nor its delete").isFalse();
        assertThat(versionOf(world, "db-1")).isEqualTo("8.0");

        // a fork may make again what the world deleted, and the world keeps it deleted
        JsonNode made = fork.call(mongodb, create, input("name", "db-3", "id", "db-3"), "dry_1", 5, true);
        assertThat(made.path("id").asText()).isNotBlank();
        String id = made.path("id").asText();
        if (id.equals("db-3")) {
            assertThat(fork.isDeleted("mongodb", "db-3")).isFalse();
            assertThat(world.isDeleted("mongodb", "db-3")).isTrue();
        }

        // changes stack: the world's later change shows through where the fork changed nothing
        world.call(mongodb, upgrade, input("id", "db-4", "version", "8.0"), "job_1", 6, true);
        assertThat(versionOf(fork, "db-4")).isEqualTo("8.0");
        fork.forget();
        assertThat(versionOf(fork, "db-1")).as("forgetting a fork leaves the world below as it was").isEqualTo("8.0");
        assertThat(versionOf(fork, "db-2")).isEqualTo("7.0");
    }
}

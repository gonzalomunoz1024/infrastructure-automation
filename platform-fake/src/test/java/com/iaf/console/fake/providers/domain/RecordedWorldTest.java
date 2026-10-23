package com.iaf.console.fake.providers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.content.application.ContentStore;
import com.iaf.console.fake.content.domain.ProviderDef;

class RecordedWorldTest {

    private static final ProviderDef VSPHERE = ContentStore.load().provider("vsphere").orElseThrow();

    @Test
    void readsOfACreatedObjectAnswerWhatTheCreateAskedFor() {
        RecordedWorld world = new RecordedWorld(1000);
        JsonNode created = world.call(VSPHERE, "vm.create", Json.object().put("name", "small-01").put("templateId", "tpl-1").put("cpu", 2)
                .put("memoryGb", 4), null);
        String id = created.path("id").asText();
        JsonNode read = world.call(VSPHERE, "vm.get", Json.object().put("id", id), null);
        assertThat(read.path("id").asText()).isEqualTo(id);
        assertThat(read.path("cpu").asInt()).isEqualTo(2);
        assertThat(read.path("memoryGb").asInt()).isEqualTo(4);
        assertThat(read.path("name").asText()).isEqualTo("small-01");
    }

    @Test
    void anObjectNoCreateMadeStillAnswersItsSample() {
        RecordedWorld world = new RecordedWorld(1000);
        JsonNode read = world.call(VSPHERE, "vm.get", Json.object().put("id", "vm-77"), null);
        assertThat(read.path("cpu").asInt()).isEqualTo(4);
        assertThat(read.path("memoryGb").asInt()).isEqualTo(16);
    }
}

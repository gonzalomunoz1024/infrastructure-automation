package com.iaf.platform.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** X-Workflow-Version and ?version= on the REST surface. */
class VersionPinTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theHeaderOrTheQueryParameterPinsTheCall() {
        assertThat(VersionPin.of("1.2.0", null)).isEqualTo("1.2.0");
        assertThat(VersionPin.of(null, " 1.1.0 ")).isEqualTo("1.1.0");
        assertThat(VersionPin.of("1.2.0", "1.2.0")).isEqualTo("1.2.0");
        assertThat(VersionPin.of("2.0.0-rc.1+build.7", null)).isEqualTo("2.0.0-rc.1+build.7");
        assertThat(VersionPin.of(null, null)).isNull();
        assertThat(VersionPin.of("  ", "")).isNull();
    }

    @Test
    void twoDifferentVersionsOrSomethingElseAreRefused() {
        assertThatThrownBy(() -> VersionPin.of("1.2.0", "1.1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("X-Workflow-Version 1.2.0 and ?version=1.1.0 name different versions");
        assertThatThrownBy(() -> VersionPin.of("latest; drop", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("X-Workflow-Version is a workflow version like 1.2.0");
        assertThatThrownBy(() -> VersionPin.of(null, "1".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionPin.clean("../1.0.0", "version")).hasMessage("version is a workflow version like 1.2.0");
    }

    @Test
    void onAReadWhoseInputHasAVersionFieldTheQueryParameterStaysInput() throws Exception {
        JsonNode withVersion = JSON.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"version\":{\"type\":\"string\"}}}");
        JsonNode without = JSON.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        assertThat(VersionPin.queryPins("GET", withVersion)).isFalse();
        assertThat(VersionPin.queryPins("GET", without)).isTrue();
        assertThat(VersionPin.queryPins("GET", null)).isTrue();
        assertThat(VersionPin.queryPins("POST", withVersion)).isTrue();
    }
}

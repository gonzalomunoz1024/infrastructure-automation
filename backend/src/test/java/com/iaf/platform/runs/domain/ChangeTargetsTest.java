package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ChangeTargetsTest {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void thePathParametersOfTheActionNameTheTarget() throws Exception {
        JsonNode in = json.readTree("{\"clusterId\":\"c1\",\"vmId\":\"vm-9\",\"force\":true}");

        assertThat(ChangeTargets.of("/clusters/{clusterId}/vms/{vmId}", in, null)).isEqualTo("c1/vm-9");
    }

    @Test
    void withoutPathParametersAnIdOrNameOfTheInputDoes() throws Exception {
        assertThat(ChangeTargets.of(null, json.readTree("{\"id\":\"vm-1\",\"name\":\"web\"}"), null)).isEqualTo("vm-1");
        assertThat(ChangeTargets.of("/vms", json.readTree("{\"name\":\"web-01\"}"), null)).isEqualTo("web-01");
        assertThat(ChangeTargets.of(null, json.readTree("{\"size\":2,\"databaseName\":\"orders\"}"), null)).isEqualTo("orders");
    }

    @Test
    void aCreateFallsBackToTheIdItAnswered() throws Exception {
        assertThat(ChangeTargets.of("/vms", json.readTree("{\"cpu\":2}"), json.readTree("{\"id\":\"vm-new\"}"))).isEqualTo("vm-new");
    }

    @Test
    void nothingToNameIsNull() throws Exception {
        assertThat(ChangeTargets.of(null, json.readTree("{\"cpu\":2}"), json.readTree("{\"ok\":true}"))).isNull();
        assertThat(ChangeTargets.of(null, null, null)).isNull();
    }
}

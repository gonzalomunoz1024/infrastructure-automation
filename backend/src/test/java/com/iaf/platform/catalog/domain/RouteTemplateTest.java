package com.iaf.platform.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RouteTemplateTest {

    @Test
    void compilesPathParametersIntoNamedCaptures() {
        RouteTemplate t = RouteTemplate.compile("POST", "/api/v1/vms/{vmId}:restart");
        assertThat(t.params()).containsExactly("vmId");
        assertThat(t.match("POST", "/api/v1/vms/vm-1042:restart")).contains(Map.of("vmId", "vm-1042"));
        assertThat(t.match("POST", "/api/v1/vms/vm-1042")).isEmpty();
        assertThat(t.match("POST", "/api/v1/vms/vm-1042:restart:batch")).isEmpty();
        assertThat(t.match("POST", "/api/v1/vms/a/b:restart")).isEmpty();
    }

    @Test
    void theMethodMustMatch() {
        RouteTemplate t = RouteTemplate.compile("DELETE", "/api/v1/vms/{vmId}");
        assertThat(t.match("DELETE", "/api/v1/vms/vm-1")).contains(Map.of("vmId", "vm-1"));
        assertThat(t.match("GET", "/api/v1/vms/vm-1")).isEmpty();
        assertThat(t.match("delete", "/api/v1/vms/vm-1")).isPresent();
    }

    @Test
    void aParameterNeverSwallowsASuffix() {
        RouteTemplate plain = RouteTemplate.compile("DELETE", "/api/v1/vms/{vmId}");
        RouteTemplate batch = plain.withSuffix("POST", ":batch");
        assertThat(plain.match("DELETE", "/api/v1/vms/vm-1:batch")).isEmpty();
        assertThat(batch.match("POST", "/api/v1/vms/vm-1:batch")).contains(Map.of("vmId", "vm-1"));
        assertThat(batch.template()).isEqualTo("/api/v1/vms/{vmId}:batch");
    }

    @Test
    void literalPartsAreQuoted() {
        RouteTemplate t = RouteTemplate.compile("POST", "/api/v1/images:build");
        assertThat(t.match("POST", "/api/v1/images:build")).contains(Map.of());
        assertThat(t.match("POST", "/api/v1/imagesXbuild")).isEmpty();
        assertThat(t.match("POST", "/api/v1/images:build/")).isPresent();
    }

    @Test
    void severalParameters() {
        RouteTemplate t = RouteTemplate.compile("PUT", "/api/v1/clusters/{clusterId}/nodes/{node-id}");
        assertThat(t.match("PUT", "/api/v1/clusters/c1/nodes/n2")).contains(Map.of("clusterId", "c1", "node-id", "n2"));
        assertThat(t.expand(Map.of("clusterId", "c1", "node-id", "n2"))).isEqualTo("/api/v1/clusters/c1/nodes/n2");
    }

    @Test
    void refusesABrokenTemplate() {
        assertThatThrownBy(() -> RouteTemplate.compile("POST", "vms")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RouteTemplate.compile("POST", "/api/v1/{a}/{a}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservedNounsAreTheFirstSegment() {
        assertThat(ReservedNouns.firstSegment("/api/v1/jobs/job_1")).isEqualTo("jobs");
        assertThat(ReservedNouns.firstSegment("/api/v1/workflows:validate")).isEqualTo("workflows");
        assertThat(ReservedNouns.firstSegment("/api/v1/vms")).isEqualTo("vms");
        assertThat(ReservedNouns.firstSegment("/openapi.json")).isNull();
        assertThat(ReservedNouns.isReserved("/api/v1/openapi.json")).isTrue();
        assertThat(ReservedNouns.isReserved("/api/v1/mcp")).isTrue();
        assertThat(ReservedNouns.isReserved("/api/v1/change-requests/cr_1:merge")).isTrue();
        assertThat(ReservedNouns.isGenerated("/api/v1/vms/vm-1:restart")).isTrue();
        assertThat(ReservedNouns.isGenerated("/api/v1/")).isFalse();
        assertThat(ReservedNouns.isGenerated("/actuator/health")).isFalse();
    }
}

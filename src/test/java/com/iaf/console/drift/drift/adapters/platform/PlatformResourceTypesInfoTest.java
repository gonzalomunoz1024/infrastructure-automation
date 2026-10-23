package com.iaf.console.drift.drift.adapters.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.platform.model.ResourceType;

/** What monitoring and rightsizing take from a platform type: its metrics read and the numbers its schema allows for a field. */
class PlatformResourceTypesInfoTest {

    private static ResourceType type(Map<String, Object> observe) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("cpu", Map.of("type", "integer", "enum", List.of(2, 4, 8), "x-console", Map.of("mutable", true)));
        properties.put("memoryGb", Map.of("type", "number", "enum", List.of(8, 16.5, 32)));
        properties.put("size", Map.of("type", "string", "enum", List.of("small", "medium")));
        properties.put("owner", Map.of("type", "string"));
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("name", "vm");
        type.put("plural", "vms");
        type.put("service", "compute");
        type.put("schema", Map.of("type", "object", "properties", properties));
        type.put("observe", observe);
        return Json.MAPPER.convertValue(type, ResourceType.class);
    }

    @Test
    void theMetricsReadAndTheAllowedNumbersAreKept() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cpuPercent", "{{ $json.cpu.usage }}");
        map.put("memoryPercent", "{{ $json.memory.usage }}");
        TypeInfo info = PlatformResourceTypes.info(type(Map.of("provider", "vsphere", "action", "vm.get", "metrics", Map.of("action", "vm.metrics",
                "map", map))));
        assertThat(info.hasMetrics()).isTrue();
        assertThat(info.metrics().action()).isEqualTo("vm.metrics");
        assertThat(info.metrics().series()).containsExactly("cpuPercent", "memoryPercent");
        assertThat(info.enums()).containsOnlyKeys("cpu", "memoryGb");
        assertThat(info.enums().get("cpu")).containsExactly(2.0, 4.0, 8.0);
        assertThat(info.enums().get("memoryGb")).containsExactly(8.0, 16.5, 32.0);
        assertThat(info.mutable()).containsExactly("cpu");
    }

    @Test
    void aTypeWithoutAMetricsReadHasNone() {
        TypeInfo info = PlatformResourceTypes.info(type(Map.of("provider", "vsphere", "action", "vm.get")));
        assertThat(info.metrics()).isNull();
        assertThat(info.hasMetrics()).isFalse();
        TypeInfo noProvider = PlatformResourceTypes.info(type(Map.of("metrics", Map.of("action", "vm.metrics"))));
        assertThat(noProvider.metrics().series()).isEmpty();
        assertThat(noProvider.hasMetrics()).as("no provider to read it from").isFalse();
        TypeInfo old = new TypeInfo("vm", "vms", "compute", "vsphere", "vm.get", Map.of(), null, null, null, false, false, null, null);
        assertThat(old.enums()).isEmpty();
        assertThat(old.hasMetrics()).isFalse();
    }
}

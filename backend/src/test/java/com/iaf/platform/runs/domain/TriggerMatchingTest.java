package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.TriggerMatching.Match;

class TriggerMatchingTest {

    @Test
    void aGrafanaCriticalVmAlertStartsVmRestartWithTheTriggerInput() {
        JsonNode alert = SampleContent.json(Map.of(
                "source", "grafana",
                "labels", Map.of("alertname", "VMUnresponsive", "severity", "critical", "vm_id", "vm-1042", "team", "compute"),
                "annotations", Map.of("summary", "vm-1042 stopped answering")));
        List<Match> matches = TriggerMatching.alerts(SampleContent.workflows(), alert);
        assertThat(matches).singleElement().satisfies(m -> {
            assertThat(m.workflow().name()).isEqualTo("vm-restart");
            assertThat(m.input().path("vmId").asText()).isEqualTo("vm-1042");
            assertThat(m.input().path("reason").asText()).isEqualTo("vm-1042 stopped answering");
        });
    }

    @Test
    void everyLabelOfTheMatchMustBeEqual() {
        JsonNode warning = SampleContent.json(Map.of("source", "grafana",
                "labels", Map.of("alertname", "VMUnresponsive", "severity", "warning", "vm_id", "vm-1042")));
        assertThat(TriggerMatching.alerts(SampleContent.workflows(), warning)).isEmpty();
        JsonNode otherSource = SampleContent.json(Map.of("source", "datadog",
                "labels", Map.of("alertname", "VMUnresponsive", "severity", "critical", "vm_id", "vm-1042")));
        assertThat(TriggerMatching.alerts(SampleContent.workflows(), otherSource)).isEmpty();
    }

    @Test
    void aCriticalScannerFindingStartsPatchCve() {
        JsonNode alert = SampleContent.json(Map.of("source", "vuln-scanner",
                "labels", Map.of("severity", "critical", "vm_id", "vm-2001", "cve", "CVE-2026-1234")));
        List<Match> matches = TriggerMatching.alerts(SampleContent.workflows(), alert);
        assertThat(matches).singleElement().satisfies(m -> {
            assertThat(m.workflow().name()).isEqualTo("patch-cve");
            assertThat(m.input().path("cve").asText()).isEqualTo("CVE-2026-1234");
            assertThat(m.input().path("severity").asText()).isEqualTo("critical");
        });
    }

    @Test
    void eventTopicsMatchExactlyOrByGlob() {
        assertThat(TriggerMatching.matchesTopic("vm.created", "vm.created")).isTrue();
        assertThat(TriggerMatching.matchesTopic("vm.*", "vm.deleted")).isTrue();
        assertThat(TriggerMatching.matchesTopic("vm.created", "vm.deleted")).isFalse();
        assertThat(TriggerMatching.matchesTopic(null, "vm.deleted")).isFalse();
        assertThat(TriggerMatching.events(SampleContent.workflows(), "vm.created", SampleContent.json(Map.of()))).isEmpty();
    }

    @Test
    void matchHelpersTreatMissingPiecesAsNoMatch() {
        assertThat(TriggerMatching.matchesAlert(null, SampleContent.json(Map.of("source", "x")))).isFalse();
        assertThat(TriggerMatching.matchesAlert(SampleContent.json(Map.of("source", "x")), null)).isFalse();
        assertThat(TriggerMatching.matchesAlert(SampleContent.json(Map.of("source", "x")), SampleContent.json(Map.of("source", "x")))).isTrue();
        assertThat(TriggerMatching.matchesAlert(SampleContent.json(Map.of("labels", Map.of("a", "1"))), SampleContent.json(Map.of("source", "x")))).isFalse();
    }
}

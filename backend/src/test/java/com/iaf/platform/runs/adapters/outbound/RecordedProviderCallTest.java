package com.iaf.platform.runs.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCall;

/** The demo provider adapter: samples, a world that remembers, and dry runs on a fork of it. */
class RecordedProviderCallTest {

    final RunsTestWiring w = new RunsTestWiring();
    final ProviderDefinition mongodb = SampleContent.provider("mongodb");
    final Action get = mongodb.action("cluster.get").orElseThrow();
    final Action upgrade = mongodb.action("cluster.upgrade").orElseThrow();

    JsonNode call(String jobId, Action action, Map<String, Object> input, boolean dryRun) {
        return w.calls.call(new ProviderCall(jobId, 1, mongodb, action, "test", SampleContent.json(input), dryRun)).block();
    }

    @Test
    void aDryRunReadsBackItsOwnChangesAndLeavesTheWorldAsItWas() {
        call("dry_1", upgrade, Map.of("id", "db-1", "version", "8.0"), true);
        assertThat(call("dry_1", get, Map.of("id", "db-1"), true).path("version").asText()).as("the same dry run reads its upgrade").isEqualTo("8.0");
        assertThat(call("dry_2", get, Map.of("id", "db-1"), true).path("version").asText()).as("another dry run does not").isEqualTo("7.0");
        assertThat(call("job_1", get, Map.of("id", "db-1"), false).path("version").asText()).as("nor a real job").isEqualTo("7.0");
        assertThat(w.calls.world().isDeleted("mongodb", "db-1")).isFalse();
        verify(w.telemetry, times(1)).record(any());
    }

    @Test
    void aRealChangeIsSeenByLaterDryRuns() {
        call("job_1", upgrade, Map.of("id", "db-1", "version", "8.0"), false);
        assertThat(call("dry_9", get, Map.of("id", "db-1"), true).path("version").asText()).isEqualTo("8.0");
        verify(w.telemetry, times(1)).record(any());
    }

    @Test
    void aDryRunWithoutAJobIdNeverTouchesTheWorld() {
        call(null, upgrade, Map.of("id", "db-1", "version", "8.0"), true);
        assertThat(call("job_1", get, Map.of("id", "db-1"), false).path("version").asText()).isEqualTo("7.0");
        verify(w.telemetry, never()).record(org.mockito.ArgumentMatchers.argThat(s -> s.jobId() == null));
    }
}

package com.iaf.platform.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.providers.adapters.outbound.InMemoryProviderAlertState;
import com.iaf.platform.providers.domain.Health;
import com.iaf.platform.providers.domain.ProviderAlert;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.outbound.ProviderAlertStatePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;

class ProviderAlertEvaluatorTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String YAML = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: fleet, title: Fleet API, owner: team-compute, version: 1.0.0 }
            spec:
              type: openapi
              spec: { url: https://fleet.example.com/openapi.json, sha256: 9f2c1b7e0a4d5c6b8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d }
              endpoints: { dev: https://fleet-dev.example.com }
              auth: { type: bearer, credential: vault:fleet/token }
              actions:
                - { id: vm.get, operation: getVm, effect: read, retry: safe }
              slo: { successRate: 99.5, p95Ms: 800 }
              alerts: { channel: "#compute-alerts" }
            """;

    ProviderStorePort store;
    TelemetryPort telemetry;
    EventBus events;
    List<DomainEvent> published;
    InMemoryProviderAlertState state;
    ProviderAlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        store = mock(ProviderStorePort.class);
        telemetry = mock(TelemetryPort.class);
        events = new EventBus(CLOCK);
        published = new ArrayList<>();
        events.register(published::add);
        when(store.all()).thenReturn(List.of(new StoredProvider(ProviderYaml.parse(YAML), YAML, "providers/team-compute/fleet.yaml", NOW, "content")));
        state = new InMemoryProviderAlertState();
        evaluator = new ProviderAlertEvaluator(store, telemetry, events, CLOCK, state);
    }

    static List<CallSample> samples(Instant from, int ok, int failed, String errorClass) {
        List<CallSample> out = new ArrayList<>();
        for (int i = 0; i < ok; i++) {
            out.add(new CallSample(from.plusSeconds(i + 1), "fleet", "vm.get", "dev", true, 100, null, "job_ok", null, null));
        }
        for (int i = 0; i < failed; i++) {
            out.add(new CallSample(from.plusSeconds(ok + i + 1), "fleet", "vm.get", "dev", false, 100, errorClass, "job_" + i, null, null));
        }
        return out;
    }

    @Test
    void aWorseningBandRaisesOneAlertAndEachFailedCallAWarning() {
        when(telemetry.samples(isNull(), any())).thenReturn(samples(NOW.minusSeconds(100), 10, 0, null));
        assertThat(evaluator.evaluateAt(NOW)).isEmpty();
        assertThat(evaluator.lastBand("fleet")).isEqualTo(Health.Status.HEALTHY);

        when(telemetry.samples(isNull(), any())).thenReturn(samples(NOW, 7, 3, "unavailable"));
        List<ProviderAlert> raised = evaluator.evaluateAt(NOW.plusSeconds(60));
        assertThat(raised).hasSize(4);
        ProviderAlert band = raised.get(0);
        assertThat(band.severity()).isEqualTo("critical");
        assertThat(band.channel()).isEqualTo("#compute-alerts");
        assertThat(band.action()).isNull();
        assertThat(band.message()).startsWith("Health is failing: Success rate 70% is below the 95% floor");
        assertThat(raised.subList(1, 4)).allMatch(a -> a.severity().equals("warning") && a.action().equals("vm.get") && a.jobId().startsWith("job_"));
        assertThat(raised.get(1).message()).isEqualTo("Call vm.get failed (unavailable) in job job_0 on dev");
        assertThat(evaluator.lastBand("fleet")).isEqualTo(Health.Status.FAILING);

        List<ProviderAlert> listed = evaluator.alerts("fleet");
        assertThat(listed).hasSize(4);
        assertThat(listed.get(0).id()).isEqualTo(raised.get(3).id());
        assertThat(listed.get(3).severity()).isEqualTo("critical");
        assertThat(published).hasSize(4).allMatch(e -> e.type().equals("provider.alert") && e.aggregateId().equals("fleet"));
        assertThat(published.get(0).data().path("severity").asText()).isEqualTo("critical");
        assertThat(published.get(0).data().has("jobId")).isFalse();
        assertThat(published.get(1).data().path("jobId").asText()).isEqualTo("job_0");
        assertThat(published.get(1).data().path("provider").asText()).isEqualTo("fleet");
        assertThat(published.get(0).data().path("channel").asText()).isEqualTo("#compute-alerts");
        assertThat(published.get(1).data().path("channel").asText()).isEqualTo("#compute-alerts");
        assertThat(published.get(0).data().path("owner").asText()).isEqualTo("team-compute");
        assertThat(published.get(0).data().path("class").asText()).isEqualTo(ProviderAlertEvaluator.HEALTH_CLASS);
        assertThat(published.get(1).data().path("class").asText()).isEqualTo("unavailable");
        assertThat(evaluator.alerts("other")).isEmpty();
    }

    @Test
    void degradedIsAWarningAndStayingBadRaisesNothingMore() {
        when(telemetry.samples(isNull(), any())).thenReturn(samples(NOW.minusSeconds(200), 98, 2, "not_found"));
        List<ProviderAlert> first = evaluator.evaluateAt(NOW);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).severity()).isEqualTo("warning");
        assertThat(first.get(0).message()).contains("Health is degraded");
        assertThat(evaluator.evaluateAt(NOW.plusSeconds(60))).isEmpty();
    }

    @Test
    void notFoundFailuresRaiseNoCallAlert() {
        when(telemetry.samples(isNull(), any())).thenReturn(samples(NOW.minusSeconds(100), 9, 1, "not_found"));
        List<ProviderAlert> raised = evaluator.evaluateAt(NOW);
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).severity()).isEqualTo("critical");
    }

    @Test
    void failedCallsAreReportedOnceAndRecoveryIsSilent() {
        when(telemetry.samples(isNull(), any())).thenReturn(samples(NOW.minusSeconds(100), 9, 1, "timeout"));
        assertThat(evaluator.evaluateAt(NOW)).hasSize(2);
        assertThat(evaluator.evaluateAt(NOW.plusSeconds(60))).isEmpty();
        when(telemetry.samples(isNull(), any())).thenReturn(samples(NOW.plusSeconds(70), 20, 0, null));
        assertThat(evaluator.evaluateAt(NOW.plusSeconds(120))).isEmpty();
        assertThat(evaluator.lastBand("fleet")).isEqualTo(Health.Status.HEALTHY);
    }

    @Test
    void keepsTheLastHundredAlertsPerProvider() {
        when(telemetry.samples(isNull(), any())).thenReturn(samples(NOW.minusSeconds(1000), 0, 150, "unavailable"));
        assertThat(evaluator.evaluateAt(NOW)).hasSize(151);
        assertThat(evaluator.alerts("fleet")).hasSize(100);
        assertThat(evaluator.alerts("fleet").get(0).jobId()).isEqualTo("job_149");
    }

    @Test
    void theScheduledEntryPointNeverThrows() {
        when(telemetry.samples(isNull(), any())).thenThrow(new IllegalStateException("telemetry down"));
        evaluator.evaluate();
        assertThat(published).isEmpty();
    }

    @Test
    void aRestartJudgesOnlyTheCallsThatFailedSinceTheLastEvaluation() {
        List<CallSample> failures = samples(NOW.minusSeconds(3600), 9, 3, "timeout");
        when(telemetry.samples(isNull(), any())).thenReturn(failures);
        assertThat(evaluator.evaluateAt(NOW)).hasSize(4);
        assertThat(state.lastEvaluatedAt()).contains(NOW);

        // a new instance over the same state, as after a restart: the calls it already judged raise nothing again
        ProviderAlertEvaluator restarted = new ProviderAlertEvaluator(store, telemetry, events, CLOCK, state);
        List<CallSample> later = new ArrayList<>(failures);
        later.add(new CallSample(NOW.plusSeconds(30), "fleet", "vm.get", "dev", false, 100, "unavailable", "job_new", null, null));
        when(telemetry.samples(isNull(), any())).thenReturn(later);
        List<ProviderAlert> raised = restarted.evaluateAt(NOW.plusSeconds(60));
        assertThat(raised).filteredOn(a -> a.action() != null).extracting(ProviderAlert::jobId).containsExactly("job_new");
        assertThat(state.lastEvaluatedAt()).contains(NOW.plusSeconds(60));

        // with nothing kept, the first evaluation judges the whole window, as before
        ProviderAlertEvaluator fresh = new ProviderAlertEvaluator(store, telemetry, events, CLOCK, new InMemoryProviderAlertState());
        assertThat(fresh.evaluateAt(NOW.plusSeconds(60))).filteredOn(a -> a.action() != null).hasSize(4);
    }

    @Test
    void aStateThatCannotBeWrittenNeverStopsAnEvaluation() {
        ProviderAlertStatePort broken = new ProviderAlertStatePort() {
            @Override
            public Optional<Instant> lastEvaluatedAt() {
                return Optional.empty();
            }

            @Override
            public void lastEvaluatedAt(Instant at) {
                throw new IllegalStateException("database down");
            }
        };
        ProviderAlertEvaluator withBrokenState = new ProviderAlertEvaluator(store, telemetry, events, CLOCK, broken);
        when(telemetry.samples(isNull(), any())).thenReturn(samples(NOW.minusSeconds(100), 9, 1, "timeout"));
        assertThat(withBrokenState.evaluateAt(NOW)).hasSize(2);
        assertThat(withBrokenState.evaluateAt(NOW.plusSeconds(60))).as("this instance still remembers").isEmpty();
    }
}

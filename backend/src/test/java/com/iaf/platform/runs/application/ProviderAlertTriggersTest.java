package com.iaf.platform.runs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.TriggerMatching;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsCommands.TriggerResult;
import com.iaf.platform.runs.ports.inbound.RunsCommands.TriggerStarted;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import reactor.core.publisher.Mono;

class ProviderAlertTriggersTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    final MovingClock clock = new MovingClock();
    final EventBus bus = new EventBus(clock);
    final RunsCommands runs = mock(RunsCommands.class);
    final ProviderAlertTriggers triggers = new ProviderAlertTriggers(bus, runs, clock);

    @BeforeEach
    void setUp() {
        when(runs.triggerAlert(any())).thenReturn(Mono.just(new TriggerResult(List.of(new TriggerStarted("vm-remediate", "job_fix1")), List.of())));
        triggers.subscribe();
    }

    static ObjectNode alert(String action, String errorClass, String jobId) {
        ObjectNode d = F.objectNode().put("provider", "vsphere").put("message", "Call " + action + " failed (" + errorClass + ")")
                .put("severity", "warning").put("channel", "#compute-alerts").put("owner", "team-compute");
        if (action != null) {
            d.put("action", action);
        }
        if (errorClass != null) {
            d.put("class", errorClass);
        }
        if (jobId != null) {
            d.put("jobId", jobId);
        }
        return d;
    }

    void raise(ObjectNode data) {
        bus.publish(ProviderAlertTriggers.ALERT_EVENT, "provider", "vsphere", "platform", data);
    }

    @Test
    void aProviderAlertIsMatchedLikeAnExternalAlert() {
        raise(alert("vm.restart", "timeout", "job_7"));

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(runs).triggerAlert(payload.capture());
        JsonNode p = payload.getValue();
        assertThat(p.get("source").asText()).isEqualTo("platform");
        assertThat(p.get("labels").get("alertname").asText()).isEqualTo("provider.alert");
        assertThat(p.get("labels").get("provider").asText()).isEqualTo("vsphere");
        assertThat(p.get("labels").get("action").asText()).isEqualTo("vm.restart");
        assertThat(p.get("labels").get("severity").asText()).isEqualTo("warning");
        assertThat(p.get("labels").get("class").asText()).isEqualTo("timeout");
        assertThat(p.get("annotations").get("summary").asText()).isEqualTo("Call vm.restart failed (timeout)");
        assertThat(p.get("annotations").get("jobId").asText()).isEqualTo("job_7");
        assertThat(p.get("startsAt").asText()).isEqualTo(NOW.toString());
    }

    @Test
    void theSameAlertStartsNothingAgainWithinTheCoolDown() {
        raise(alert("vm.restart", "timeout", "job_1"));
        raise(alert("vm.restart", "timeout", "job_2"));
        raise(alert("vm.restart", "unavailable", "job_3"));
        verify(runs, times(2)).triggerAlert(any());

        clock.now.set(NOW.plus(ProviderAlertTriggers.COOL_DOWN).plusSeconds(1));
        raise(alert("vm.restart", "timeout", "job_4"));
        verify(runs, times(3)).triggerAlert(any());
    }

    @Test
    void anAlertFromAJobAPlatformAlertStartedStartsNothing() {
        raise(alert("vm.restart", "timeout", "job_7"));
        clock.now.set(NOW.plus(Duration.ofHours(1)));

        raise(alert("vm.get", "unavailable", "job_fix1"));

        verify(runs, times(1)).triggerAlert(any());
    }

    @Test
    void oldAlertsAndOtherEventsStartNothing() {
        bus.publish(new DomainEvent("evt_old", NOW.minus(Duration.ofHours(1)), ProviderAlertTriggers.ALERT_EVENT, "provider", "vsphere", "platform",
                alert("vm.restart", "timeout", null)));
        bus.publish("job.step", "job", "job_1", "platform", F.objectNode());

        verify(runs, never()).triggerAlert(any());
    }

    @Test
    void anAlertTriggerOnPlatformLabelsMatchesTheProviderAlert() {
        WorkflowDefinition def = WorkflowYaml.parse(SampleContent.yaml("vm-restart").replace(
                "match: { source: grafana, labels: { alertname: VMUnresponsive, severity: critical } }",
                "match: { source: platform, labels: { provider: vsphere, class: timeout } }"));
        DomainEvent timeout = new DomainEvent("evt_1", NOW, ProviderAlertTriggers.ALERT_EVENT, "provider", "vsphere", "platform",
                alert("vm.restart", "timeout", "job_7"));
        DomainEvent health = new DomainEvent("evt_2", NOW, ProviderAlertTriggers.ALERT_EVENT, "provider", "vsphere", "platform",
                alert(null, "health", null));

        assertThat(TriggerMatching.alerts(List.of(def), ProviderAlertTriggers.payload(timeout))).hasSize(1);
        assertThat(TriggerMatching.alerts(List.of(def), ProviderAlertTriggers.payload(health))).isEmpty();
    }
}

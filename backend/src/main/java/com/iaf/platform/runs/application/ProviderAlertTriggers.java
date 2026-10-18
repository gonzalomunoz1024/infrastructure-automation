package com.iaf.platform.runs.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsCommands.TriggerStarted;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The platform's own provider alerts start workflows the way external alerts do: every
 * {@code provider.alert} event is matched against the published alert triggers as
 *
 * <pre>
 * { source: platform,
 *   labels: { alertname: provider.alert, provider, action, severity, class },
 *   annotations: { summary, message, jobId, channel, owner } }
 * </pre>
 *
 * so a trigger {@code match: { source: platform, labels: { provider: vsphere, class: timeout } }}
 * starts its workflow with {@code $alert} bound to that payload, as the platform identity. Two
 * guards keep remediation from feeding itself: an alert raised by a job that a platform alert
 * started starts nothing, and the same alert (provider, action, class) starts nothing again within
 * {@link #COOL_DOWN}. Events older than the cool-down (history being seeded) start nothing.
 */
@Slf4j
@Component
public class ProviderAlertTriggers {

    public static final String SOURCE = "platform";
    public static final String ALERT_EVENT = "provider.alert";
    static final Duration COOL_DOWN = Duration.ofMinutes(10);
    private static final int REMEMBERED_JOBS = 1000;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final EventBus events;
    private final RunsCommands runs;
    private final Clock clock;
    private final Map<String, Instant> lastTriggered = new ConcurrentHashMap<>();
    private final Set<String> startedByAlerts = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED_JOBS;
        }
    }));

    public ProviderAlertTriggers(EventBus events, RunsCommands runs, Clock clock) {
        this.events = events;
        this.runs = runs;
        this.clock = clock;
    }

    @PostConstruct
    public void subscribe() {
        events.register(this::on);
    }

    void on(DomainEvent event) {
        if (event == null || !ALERT_EVENT.equals(event.type())) {
            return;
        }
        Instant now = clock.instant();
        if (event.at() != null && event.at().isBefore(now.minus(COOL_DOWN))) {
            return;
        }
        JsonNode d = event.data();
        String jobId = text(d, "jobId");
        if (jobId != null && startedByAlerts.contains(jobId)) {
            log.info("provider alert from job {} (started by a platform alert) starts no workflow", jobId);
            return;
        }
        String key = text(d, "provider") + "|" + text(d, "action") + "|" + text(d, "class");
        Instant last = lastTriggered.get(key);
        if (last != null && last.isAfter(now.minus(COOL_DOWN))) {
            return;
        }
        lastTriggered.put(key, now);
        runs.triggerAlert(payload(event)).subscribe(
                result -> {
                    for (TriggerStarted s : result.started()) {
                        startedByAlerts.add(s.jobId());
                        log.info("provider alert {} started {} as {}", key, s.workflow(), s.jobId());
                    }
                },
                e -> log.warn("provider alert {} could not start its workflows: {}", key, e.toString()));
    }

    /** The alert payload an external alert source would send for this event. */
    public static JsonNode payload(DomainEvent event) {
        JsonNode d = event.data() == null ? F.objectNode() : event.data();
        ObjectNode alert = F.objectNode();
        alert.put("source", SOURCE);
        ObjectNode labels = alert.putObject("labels");
        labels.put("alertname", ALERT_EVENT);
        String provider = text(d, "provider");
        labels.put("provider", provider == null ? event.aggregateId() : provider);
        copy(d, labels, "action");
        copy(d, labels, "severity");
        copy(d, labels, "class");
        ObjectNode annotations = alert.putObject("annotations");
        if (text(d, "message") != null) {
            annotations.put("summary", text(d, "message"));
        }
        copy(d, annotations, "message");
        copy(d, annotations, "jobId");
        copy(d, annotations, "channel");
        copy(d, annotations, "owner");
        if (event.at() != null) {
            alert.put("startsAt", event.at().toString());
        }
        return alert;
    }

    private static void copy(JsonNode from, ObjectNode to, String field) {
        String v = text(from, field);
        if (v != null) {
            to.put(field, v);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String v = node.get(field).asText();
        return v.isBlank() ? null : v;
    }
}

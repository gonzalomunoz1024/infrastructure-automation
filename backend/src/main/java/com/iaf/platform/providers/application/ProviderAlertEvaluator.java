package com.iaf.platform.providers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.providers.domain.Health;
import com.iaf.platform.providers.domain.ProviderAlert;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderHealthRule;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Every minute, judges the health of every provider and raises an alert when a band gets worse
 * (degraded is a warning, failing is critical). A single failed call with a class other than
 * not_found raises a warning naming the job. The last {@value #KEEP} alerts per provider are kept
 * for {@code GET /providers/{name}/alerts}; every alert is also published as {@code provider.alert},
 * carrying the provider's {@code alerts.channel} and owner so a notifier knows where to deliver it,
 * and its {@code class} (the call's error class, or {@value #HEALTH_CLASS}) for alert triggers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderAlertEvaluator {

    public static final String ALERT_EVENT = "provider.alert";
    public static final int KEEP = 100;
    static final String IGNORED_ERROR_CLASS = "not_found";
    /** The {@code class} of an alert about the health band rather than one failed call. */
    public static final String HEALTH_CLASS = "health";

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final ProviderStorePort store;
    private final TelemetryPort telemetry;
    private final EventBus events;
    private final Clock clock;

    private final Map<String, Deque<ProviderAlert>> alerts = new ConcurrentHashMap<>();
    private final Map<String, Health.Status> lastBand = new ConcurrentHashMap<>();
    private volatile Instant lastEvaluatedAt;

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void evaluate() {
        try {
            evaluateAt(clock.instant());
        } catch (RuntimeException e) {
            log.warn("alert evaluation failed: {}", e.toString());
        }
    }

    /** One evaluation at a given instant; returns the alerts it raised. Synchronized: one evaluation at a time. */
    public synchronized List<ProviderAlert> evaluateAt(Instant now) {
        Instant since = now.minus(ProviderHealthRule.WINDOW);
        Map<String, List<CallSample>> byProvider = new HashMap<>();
        for (CallSample s : telemetry.samples(null, since)) {
            byProvider.computeIfAbsent(s.provider(), k -> new ArrayList<>()).add(s);
        }
        Instant failuresFrom = lastEvaluatedAt == null ? since : lastEvaluatedAt;
        List<ProviderAlert> raised = new ArrayList<>();
        for (StoredProvider p : store.all()) {
            ProviderDefinition def = p.definition();
            List<CallSample> samples = byProvider.getOrDefault(def.name(), List.of());
            Health health = ProviderHealthRule.judge(samples, def, now);
            Health.Status previous = lastBand.put(def.name(), health.status());
            int before = previous == null ? Health.Status.HEALTHY.rank() : previous.rank();
            if (health.status().rank() > before) {
                String severity = health.status() == Health.Status.FAILING ? ProviderAlert.CRITICAL : ProviderAlert.WARNING;
                String message = "Health is " + health.status().json() + ": " + String.join("; ", health.reasons());
                raised.add(raise(def, null, message, severity, null, HEALTH_CLASS, now));
            }
            for (CallSample s : samples) {
                if (s.ok() || s.at() == null || !s.at().isAfter(failuresFrom) || IGNORED_ERROR_CLASS.equals(s.errorClass())) {
                    continue;
                }
                String errorClass = s.errorClass() == null ? "error" : s.errorClass();
                String message = "Call " + s.action() + " failed (" + errorClass + ")" + (s.jobId() == null ? "" : " in job " + s.jobId())
                        + (s.environment() == null ? "" : " on " + s.environment());
                raised.add(raise(def, s.action(), message, ProviderAlert.WARNING, s.jobId(), errorClass, now));
            }
        }
        lastEvaluatedAt = now;
        return raised;
    }

    /** Newest first, at most {@value #KEEP}. */
    public List<ProviderAlert> alerts(String provider) {
        Deque<ProviderAlert> deque = alerts.get(provider);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            List<ProviderAlert> out = new ArrayList<>(deque);
            java.util.Collections.reverse(out);
            return out;
        }
    }

    /** The band of the last evaluation, or null before the first one. */
    public Health.Status lastBand(String provider) {
        return lastBand.get(provider);
    }

    private ProviderAlert raise(ProviderDefinition def, String action, String message, String severity, String jobId, String errorClass, Instant at) {
        String provider = def.name();
        String channel = channelOf(def);
        ProviderAlert alert = new ProviderAlert(Ids.next("alert"), at, provider, action, message, severity, jobId, channel);
        Deque<ProviderAlert> deque = alerts.computeIfAbsent(provider, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(alert);
            while (deque.size() > KEEP) {
                deque.removeFirst();
            }
        }
        ObjectNode data = F.objectNode();
        data.put("provider", provider);
        if (action != null) {
            data.put("action", action);
        }
        data.put("message", message);
        data.put("severity", severity);
        if (jobId != null) {
            data.put("jobId", jobId);
        }
        if (channel != null) {
            data.put("channel", channel);
        }
        if (def.owner() != null) {
            data.put("owner", def.owner());
        }
        if (errorClass != null) {
            data.put("class", errorClass);
        }
        events.publish(ALERT_EVENT, "provider", provider, Identity.SYSTEM.id(), data);
        log.info("provider alert [{}] {}: {}", severity, provider, message);
        return alert;
    }

    /** The provider's {@code alerts.channel}, where its alerts are meant to go, or null when it names none. */
    static String channelOf(ProviderDefinition def) {
        if (def.spec() == null || def.spec().alerts() == null || def.spec().alerts().channel() == null) {
            return null;
        }
        String channel = def.spec().alerts().channel().trim();
        return channel.isEmpty() ? null : channel;
    }
}

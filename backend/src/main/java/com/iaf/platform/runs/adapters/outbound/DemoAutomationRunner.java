package com.iaf.platform.runs.adapters.outbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.RecordedWorld;
import com.iaf.platform.runs.domain.RecordedWorld.Answer;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Mono;

/**
 * The demo {@link AutomationRunnerPort}: {@code call: ansible} answers from the ansible-type
 * provider whose git repo (or library reference) and playbook match, {@code call: terraform}
 * from the provider with the same module source (plan, apply or destroy sample),
 * {@code call: idp} from the idp provider whose action id is the tile; {@code checkMode} makes
 * a playbook report {@code changed: 0}. HTTP never leaves the process ({@code { status: 200,
 * body: {} }}), activities answer {@code { ok: true, activity }}, notify {@code { delivered: true,
 * channel, message }} and scripts run on {@code MiniScript}, a tiny deterministic JavaScript subset.
 */
@Component
@Order(100)
public class DemoAutomationRunner implements AutomationRunnerPort {

    private static final Set<StepType> SUPPORTED = EnumSet.of(StepType.HTTP, StepType.ANSIBLE, StepType.TERRAFORM, StepType.IDP,
            StepType.ACTIVITY, StepType.NOTIFY, StepType.SCRIPT);

    private final ProviderQueries providers;
    private final TelemetryPort telemetry;
    private final JobProjection projection;
    private final Clock clock;

    public DemoAutomationRunner(ProviderQueries providers, TelemetryPort telemetry, JobProjection projection, Clock clock) {
        this.providers = providers;
        this.telemetry = telemetry;
        this.projection = projection;
        this.clock = clock;
    }

    @Override
    public boolean supports(StepType type) {
        return SUPPORTED.contains(type);
    }

    @Override
    public Mono<JsonNode> run(RunRequest request) {
        if (request == null || request.type() == null) {
            return Mono.error(new IllegalArgumentException("an automation request needs a step type"));
        }
        long latency = ThreadLocalRandom.current().nextInt(RecordedProviderCall.MIN_LATENCY_MS, RecordedProviderCall.MAX_LATENCY_MS + 1);
        return providers.all().collectList().defaultIfEmpty(java.util.List.of())
                .delayElement(Duration.ofMillis(latency))
                .map(list -> {
                    Instant at = clock.instant().minusMillis(latency);
                    Answer answer = RecordedWorld.automation(list, request.type(), request.with(), request.jobId(), request.seq(), request.checkMode());
                    if (answer.provider() != null && !request.dryRun()) {
                        Job job = request.jobId() == null ? null : projection.get(request.jobId()).orElse(null);
                        telemetry.record(new CallSample(at, answer.provider().name(), answer.action().id(), request.environment(), true, latency, null,
                                request.jobId(), job == null ? null : job.workflow(), job == null ? null : job.version()));
                    }
                    return answer.output();
                });
    }
}

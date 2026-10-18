package com.iaf.platform.runs.adapters.outbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.RecordedWorld;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;

import reactor.core.publisher.Mono;

/**
 * The demo {@link ProviderCallPort}: every action answers from its recorded {@code sample},
 * resolved with {@code $input} = the call input and {@code $job} = {@code { id, seq }}, after a
 * simulated 20-80 ms. The world remembers: a {@code delete} makes later reads of the same id
 * answer {@code not_found} (so {@code verify: { expect: notFound }} comes true), a {@code change}
 * is read back. Dry runs never change the world. Every call lands in the telemetry as a sample.
 */
@Component
public class RecordedProviderCall implements ProviderCallPort {

    public static final int MIN_LATENCY_MS = 20;
    public static final int MAX_LATENCY_MS = 80;

    private final RecordedWorld world = new RecordedWorld();
    private final TelemetryPort telemetry;
    private final JobProjection projection;
    private final Clock clock;

    public RecordedProviderCall(TelemetryPort telemetry, JobProjection projection, Clock clock) {
        this.telemetry = telemetry;
        this.projection = projection;
        this.clock = clock;
    }

    @Override
    public Mono<JsonNode> call(ProviderCall call) {
        if (call == null || call.provider() == null || call.action() == null) {
            return Mono.error(new IllegalArgumentException("a provider call needs a provider and an action"));
        }
        long latency = ThreadLocalRandom.current().nextInt(MIN_LATENCY_MS, MAX_LATENCY_MS + 1);
        return Mono.delay(Duration.ofMillis(latency)).flatMap(tick -> Mono.fromCallable(() -> {
            Instant at = clock.instant().minusMillis(latency);
            try {
                JsonNode out = world.call(call.provider(), call.action(), call.input(), call.jobId(), call.seq(), !call.dryRun());
                record(call, at, latency, true, null);
                return out;
            } catch (ProviderCallException e) {
                record(call, at, latency, false, e.errorClass());
                throw e;
            }
        }));
    }

    private void record(ProviderCall call, Instant at, long latency, boolean ok, String errorClass) {
        if (call.dryRun()) {
            return;
        }
        Job job = call.jobId() == null ? null : projection.get(call.jobId()).orElse(null);
        telemetry.record(new CallSample(at, call.provider().name(), call.action().id(), call.environment(), ok, latency, errorClass,
                call.jobId(), job == null ? null : job.workflow(), job == null ? null : job.version(), call.provider().metadata().version()));
    }

    /** The world this adapter answers from, for tests and the seed. */
    public RecordedWorld world() {
        return world;
    }
}

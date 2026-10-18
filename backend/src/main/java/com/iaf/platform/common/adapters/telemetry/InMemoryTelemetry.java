package com.iaf.platform.common.adapters.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.iaf.platform.common.ports.TelemetryPort;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The demo telemetry: a bounded in-memory sample store (the last {@value #RETENTION_DAYS} days,
 * at most {@value #MAX_SAMPLES} samples) plus Micrometer meters so {@code /actuator/prometheus}
 * shows {@code provider_call} (timer, tags provider/action/outcome) and {@code provider_errors}
 * (counter, tags provider/action/class). Production keeps the meters and adds an OTel exporter.
 */
@Component
public class InMemoryTelemetry implements TelemetryPort {

    public static final String CALL_TIMER = "provider.call";
    public static final String ERROR_COUNTER = "provider.errors";
    public static final int RETENTION_DAYS = 30;
    public static final int MAX_SAMPLES = 200_000;
    private static final int PRUNE_EVERY = 1000;

    private final MeterRegistry registry;
    private final Clock clock;
    private final ConcurrentLinkedDeque<CallSample> samples = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicInteger sinceLastPrune = new AtomicInteger();

    public InMemoryTelemetry(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public void record(CallSample sample) {
        if (sample == null) {
            return;
        }
        CallSample s = sample.at() == null ? withNow(sample) : sample;
        samples.addLast(s);
        if (size.incrementAndGet() > MAX_SAMPLES) {
            if (samples.pollFirst() != null) {
                size.decrementAndGet();
            }
        }
        if (sinceLastPrune.incrementAndGet() >= PRUNE_EVERY) {
            sinceLastPrune.set(0);
            prune(clock.instant().minus(Duration.ofDays(RETENTION_DAYS)));
        }
        String provider = tag(s.provider());
        String action = tag(s.action());
        Timer.builder(CALL_TIMER)
                .description("Provider call duration")
                .tags("provider", provider, "action", action, "outcome", s.ok() ? "ok" : "error")
                .register(registry)
                .record(Math.max(0, s.durationMs()), TimeUnit.MILLISECONDS);
        if (!s.ok()) {
            Counter.builder(ERROR_COUNTER)
                    .description("Provider call errors by class")
                    .tags("provider", provider, "action", action, "class", tag(s.errorClass()))
                    .register(registry)
                    .increment();
        }
    }

    @Override
    public List<CallSample> samples(String provider, Instant since) {
        Instant floor = clock.instant().minus(Duration.ofDays(RETENTION_DAYS));
        Instant from = since == null || since.isBefore(floor) ? floor : since;
        return samples.stream()
                .filter(s -> provider == null || provider.equals(s.provider()))
                .filter(s -> s.at() != null && !s.at().isBefore(from))
                .sorted(Comparator.comparing(CallSample::at))
                .toList();
    }

    /** How many samples are held, for tests and the actuator. */
    public int size() {
        return size.get();
    }

    private void prune(Instant floor) {
        samples.removeIf(s -> {
            boolean old = s.at() == null || s.at().isBefore(floor);
            if (old) {
                size.decrementAndGet();
            }
            return old;
        });
    }

    private CallSample withNow(CallSample s) {
        return new CallSample(clock.instant(), s.provider(), s.action(), s.environment(), s.ok(), s.durationMs(), s.errorClass(), s.jobId(), s.workflow(), s.version(),
                s.providerVersion());
    }

    private static String tag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}

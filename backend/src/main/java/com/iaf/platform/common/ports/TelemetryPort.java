package com.iaf.platform.common.ports;

import java.time.Instant;
import java.util.List;

/**
 * Timings and outcomes of every provider call, from which health, the admin charts and the
 * canary gates are judged. The demo adapter keeps samples in memory (and seeds history);
 * production also publishes Micrometer meters for Prometheus / OTel.
 */
public interface TelemetryPort {

    record CallSample(Instant at, String provider, String action, String environment, boolean ok, long durationMs,
            String errorClass, String jobId, String workflow, String version) {
    }

    void record(CallSample sample);

    /** Samples of one provider since an instant, oldest first. {@code provider} null means every provider. */
    List<CallSample> samples(String provider, Instant since);
}

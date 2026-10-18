package com.iaf.platform.common.tracing;

import java.util.function.Supplier;

import org.springframework.boot.actuate.autoconfigure.tracing.SdkTracerProviderBuilderCustomizer;
import org.springframework.stereotype.Component;

import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;

/**
 * The OpenTelemetry SDK's id generator, except that a root span started inside
 * {@link #root(String, Supplier)} gets the trace id asked for. A job's trace id is minted before
 * its span exists (a trigger, a schedule or a rerun has no request to inherit one from), so this
 * is how the job's span becomes the root of the trace its {@code traceId} names instead of hanging
 * under a parent nobody exported. Installed on the SDK by Spring Boot through the customizer.
 */
@Component
public class RequestedTraceIds implements IdGenerator, SdkTracerProviderBuilderCustomizer {

    private final IdGenerator random = IdGenerator.random();
    private final ThreadLocal<String> requested = new ThreadLocal<>();
    private volatile boolean installed;

    @Override
    public void customize(SdkTracerProviderBuilder builder) {
        builder.setIdGenerator(this);
        installed = true;
    }

    /** True once an SDK generates its ids here, so {@link #root} can work. */
    public boolean installed() {
        return installed;
    }

    /** Run {@code start} so that the root span it starts on this thread gets {@code traceId}. */
    public <T> T root(String traceId, Supplier<T> start) {
        if (!TraceIds.isTraceId(traceId)) {
            return start.get();
        }
        requested.set(traceId);
        try {
            return start.get();
        } finally {
            requested.remove();
        }
    }

    @Override
    public String generateTraceId() {
        String id = requested.get();
        if (id != null) {
            requested.remove();
            return id;
        }
        return random.generateTraceId();
    }

    @Override
    public String generateSpanId() {
        return random.generateSpanId();
    }
}

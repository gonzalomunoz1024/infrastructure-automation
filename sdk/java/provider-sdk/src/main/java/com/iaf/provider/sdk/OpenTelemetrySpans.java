package com.iaf.provider.sdk;

import java.util.Map;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

/**
 * The OpenTelemetry side of {@link CallTracing}, in a class of its own so that nothing loads the
 * OpenTelemetry API unless it is on the classpath.
 */
final class OpenTelemetrySpans {

    static final String INSTRUMENTATION = "com.iaf.provider.sdk";

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private OpenTelemetrySpans() {
    }

    /** A span, child of the remote span the traceparent names, current until the scope closes. */
    static CallTracing.Scope start(String name, String traceparent, Map<String, String> attributes, int attempt) {
        Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), Map.of("traceparent", traceparent), GETTER);
        SpanBuilder builder = GlobalOpenTelemetry.getTracer(INSTRUMENTATION).spanBuilder(name).setParent(parent).setSpanKind(SpanKind.SERVER);
        attributes.forEach((k, v) -> {
            if (v != null) {
                builder.setAttribute(k, v);
            }
        });
        builder.setAttribute("attempt", (long) attempt);
        Span span = builder.startSpan();
        io.opentelemetry.context.Scope current = span.makeCurrent();
        return new CallTracing.Scope() {
            @Override
            public void failed(Throwable error, String errorClass) {
                span.setAttribute("error.class", errorClass);
                span.recordException(error);
                span.setStatus(StatusCode.ERROR, errorClass);
            }

            @Override
            public void close() {
                current.close();
                span.end();
            }
        };
    }
}

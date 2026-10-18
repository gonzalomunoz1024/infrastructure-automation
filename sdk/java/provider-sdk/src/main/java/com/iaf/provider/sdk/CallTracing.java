package com.iaf.provider.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Continues the platform's trace while a handler runs. The call's {@code traceparent} and trace id
 * are in the logging MDC ({@code traceparent}, {@code traceId}) for the handler's log lines, and
 * when the OpenTelemetry API is on the worker's classpath (an optional dependency) a span
 * {@code run <provider>.<action>}, child of the platform's step span, is current, so the clients
 * the handler calls carry the trace on. Without the API, or without a traceparent, nothing else
 * changes; the span is a no-op until the worker installs an OpenTelemetry SDK (the Java agent or
 * {@code GlobalOpenTelemetry.set}).
 */
final class CallTracing {

    static final String MDC_TRACEPARENT = "traceparent";
    static final String MDC_TRACE_ID = "traceId";

    /** {@code version-traceid-spanid-flags}, lowercase hex, an all-zero trace or span id is invalid. */
    private static final Pattern TRACEPARENT = Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final Logger log = LoggerFactory.getLogger(CallTracing.class);
    private static final boolean OPENTELEMETRY = present("io.opentelemetry.api.GlobalOpenTelemetry");

    /** What stays open while the handler runs; closing it ends the span and restores the MDC. */
    interface Scope extends AutoCloseable {

        /** The call failed, with this error class. */
        void failed(Throwable error, String errorClass);

        @Override
        void close();
    }

    private CallTracing() {
    }

    /** Whether the text is a W3C traceparent this SDK continues. */
    static boolean valid(String traceparent) {
        if (traceparent == null) {
            return false;
        }
        Matcher m = TRACEPARENT.matcher(traceparent);
        return m.matches() && !m.group(1).equals("ff") && !m.group(2).matches("0+") && !m.group(3).matches("0+");
    }

    static Scope start(Provider provider, String action, Envelope call) {
        JobInfo job = call.job() == null ? new JobInfo(null, null, null, null) : call.job();
        String traceparent = valid(job.traceparent()) ? job.traceparent() : null;
        String traceId = job.traceId() != null ? job.traceId() : traceparent == null ? null : traceparent.substring(3, 35);
        String previousTraceparent = MDC.get(MDC_TRACEPARENT);
        String previousTraceId = MDC.get(MDC_TRACE_ID);
        put(MDC_TRACEPARENT, traceparent);
        put(MDC_TRACE_ID, traceId);
        if (job.traceparent() != null && traceparent == null) {
            log.debug("call {} of job {} carries a traceparent that is not W3C trace context: {}", action, job.id(), job.traceparent());
        }
        Scope span = traceparent != null && OPENTELEMETRY ? span(provider, action, call, job, traceparent) : null;
        return new Scope() {
            @Override
            public void failed(Throwable error, String errorClass) {
                if (span != null) {
                    span.failed(error, errorClass);
                }
            }

            @Override
            public void close() {
                try {
                    if (span != null) {
                        span.close();
                    }
                } finally {
                    put(MDC_TRACEPARENT, previousTraceparent);
                    put(MDC_TRACE_ID, previousTraceId);
                }
            }
        };
    }

    private static Scope span(Provider provider, String action, Envelope call, JobInfo job, String traceparent) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("provider", provider.name());
        attributes.put("provider.version", provider.version());
        attributes.put("action", action);
        attributes.put("job.id", job.id());
        attributes.put("workflow", job.workflow());
        attributes.put("workflow.version", job.version());
        attributes.put("step", call.step());
        attributes.put("environment", call.environment());
        try {
            return OpenTelemetrySpans.start("run " + provider.name() + "." + action, traceparent, attributes, call.attempt());
        } catch (RuntimeException | LinkageError e) {
            log.debug("call {} of job {}: no span ({})", action, job.id(), e.toString());
            return null;
        }
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, CallTracing.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}

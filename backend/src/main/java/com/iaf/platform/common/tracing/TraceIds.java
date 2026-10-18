package com.iaf.platform.common.tracing;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C trace context ids: a trace id is 32 lowercase hex characters, a span id 16, neither all
 * zeros, and {@code traceparent} carries both ({@code 00-<trace id>-<span id>-<flags>}). A job's
 * {@code traceId} is always such a trace id, so it is the id of the job's trace.
 */
public final class TraceIds {

    public static final String TRACEPARENT = "traceparent";

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-f]{16}$");
    private static final Pattern HEADER = Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(-.*)?$");
    private static final String ZERO_TRACE = "0".repeat(32);
    private static final String ZERO_SPAN = "0".repeat(16);

    private TraceIds() {
    }

    /** A {@code traceparent} taken apart. */
    public record Parent(String traceId, String spanId, boolean sampled) {
    }

    /** A fresh random trace id. */
    public static String newTraceId() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String id;
        do {
            id = hex(r.nextLong()) + hex(r.nextLong());
        } while (id.equals(ZERO_TRACE));
        return id;
    }

    /** A fresh random span id. */
    public static String newSpanId() {
        String id;
        do {
            id = hex(ThreadLocalRandom.current().nextLong());
        } while (id.equals(ZERO_SPAN));
        return id;
    }

    public static boolean isTraceId(String id) {
        return id != null && TRACE_ID.matcher(id).matches() && !id.equals(ZERO_TRACE);
    }

    public static boolean isSpanId(String id) {
        return id != null && SPAN_ID.matcher(id).matches() && !id.equals(ZERO_SPAN);
    }

    /** A {@code traceparent} header, when it is one (version {@code ff} and zero ids are not); case and surrounding blanks are forgiven. */
    public static Optional<Parent> parse(String traceparent) {
        if (traceparent == null) {
            return Optional.empty();
        }
        Matcher m = HEADER.matcher(traceparent.trim().toLowerCase());
        if (!m.matches() || "ff".equals(m.group(1)) || ("00".equals(m.group(1)) && m.group(5) != null)) {
            return Optional.empty();
        }
        if (!isTraceId(m.group(2)) || !isSpanId(m.group(3))) {
            return Optional.empty();
        }
        return Optional.of(new Parent(m.group(2), m.group(3), (Integer.parseInt(m.group(4), 16) & 1) == 1));
    }

    /** {@code 00-<traceId>-<spanId>-01} (or {@code -00} when not sampled). */
    public static String traceparent(String traceId, String spanId, boolean sampled) {
        if (!isTraceId(traceId) || !isSpanId(spanId)) {
            throw new IllegalArgumentException("traceparent needs a 32-hex trace id and a 16-hex span id");
        }
        return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
    }

    private static String hex(long v) {
        String s = Long.toHexString(v);
        return "0".repeat(16 - s.length()) + s;
    }
}

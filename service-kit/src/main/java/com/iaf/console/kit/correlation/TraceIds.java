package com.iaf.console.kit.correlation;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/** W3C trace context: parsing and minting {@code traceparent} headers and the trace ids inside them. */
public final class TraceIds {

    /** The header name, lowercase as the specification writes it. */
    public static final String TRACEPARENT = "traceparent";

    private static final Pattern TRACEPARENT_SHAPE = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private TraceIds() {
    }

    /** The parts of a traceparent header. */
    public record Parent(String traceId, String parentId, String flags) {

        /** The header text. */
        public String format() {
            return "00-" + traceId + "-" + parentId + "-" + flags;
        }

        /** The same trace with a fresh span id, for a call this service makes. */
        public Parent child() {
            return new Parent(traceId, newSpanId(), flags);
        }
    }

    /** The header's parts when it is well formed and its ids are not all zeros. */
    public static Optional<Parent> parse(String traceparent) {
        if (traceparent == null) {
            return Optional.empty();
        }
        var m = TRACEPARENT_SHAPE.matcher(traceparent.trim());
        if (!m.matches() || allZeros(m.group(1)) || allZeros(m.group(2))) {
            return Optional.empty();
        }
        return Optional.of(new Parent(m.group(1), m.group(2), m.group(3)));
    }

    /** A new sampled trace. */
    public static Parent newTrace() {
        return new Parent(newTraceId(), newSpanId(), "01");
    }

    /** A new 32-character trace id. */
    public static String newTraceId() {
        return random(16);
    }

    /** A new 16-character span id. */
    public static String newSpanId() {
        return random(8);
    }

    /** Whether a string is a trace id: 32 hex characters, not all zeros. */
    public static boolean isTraceId(String s) {
        return s != null && s.length() == 32 && s.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) && !allZeros(s);
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        do {
            RANDOM.nextBytes(b);
        } while (allZeros(HEX.formatHex(b)));
        return HEX.formatHex(b);
    }

    private static boolean allZeros(String hex) {
        for (int i = 0; i < hex.length(); i++) {
            if (hex.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }
}

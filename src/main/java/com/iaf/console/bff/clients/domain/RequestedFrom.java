package com.iaf.console.bff.clients.domain;

/**
 * Where a client called from, so the person can tell the CLI is theirs: its address (at most 64
 * characters, {@code unknown} when there is none) and its {@code User-Agent} (at most 256), with
 * control characters left out of both.
 */
public record RequestedFrom(String ip, String userAgent) {

    /** The longest address kept. */
    public static final int MAX_IP = 64;
    /** The longest user agent kept. */
    public static final int MAX_USER_AGENT = 256;

    public RequestedFrom {
        String address = clean(ip, MAX_IP);
        ip = address == null ? "unknown" : address;
        userAgent = clean(userAgent, MAX_USER_AGENT);
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < max; i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                out.append(c);
            }
        }
        String cleaned = out.toString().strip();
        return cleaned.isEmpty() ? null : cleaned;
    }
}

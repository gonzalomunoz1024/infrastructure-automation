package com.iaf.console.sdk;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/** Small pieces of HTTP the client and the sign-in share. */
final class Http {
    private Http() {}

    private static final Pattern SECONDS = Pattern.compile("\\d+");
    private static final Pattern JSON = Pattern.compile("(?i)^application/(?:[\\w.-]+\\+)?json\\b.*");

    /** {@code Retry-After} as a wait: a number of seconds, or an HTTP date; null when absent or unreadable. */
    static Duration retryAfter(String header, Instant now) {
        if (header == null || header.isBlank()) return null;
        String value = header.trim();
        if (SECONDS.matcher(value).matches()) {
            try {
                return Duration.ofSeconds(Long.parseLong(value));
            } catch (NumberFormatException e) {
                return null; // more seconds than a long holds: no wait we would honour
            }
        }
        try {
            Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long millis = Duration.between(now, at).toMillis();
            // whole seconds, rounded up, as the TypeScript SDK reads it
            return Duration.ofSeconds(Math.max(0, (millis + 999) / 1000));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static boolean isJson(String contentType) {
        return contentType != null && JSON.matcher(contentType.trim()).matches();
    }

    /** {@code application/x-www-form-urlencoded} and query strings: every byte but the unreserved ones escaped. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String form(Map<String, String> fields) {
        StringJoiner joiner = new StringJoiner("&");
        fields.forEach((k, v) -> {
            if (v != null) joiner.add(encode(k) + "=" + encode(v));
        });
        return joiner.toString();
    }

    /** {@code name=value&...}; a collection repeats its name, a null is left out. */
    static String query(Map<String, ?> query) {
        StringJoiner joiner = new StringJoiner("&");
        if (query != null) {
            query.forEach((name, value) -> {
                if (value instanceof Collection<?> values) {
                    for (Object v : values) {
                        if (v != null) joiner.add(encode(name) + "=" + encode(String.valueOf(v)));
                    }
                } else if (value != null) {
                    joiner.add(encode(name) + "=" + encode(String.valueOf(value)));
                }
            });
        }
        return joiner.toString();
    }
}

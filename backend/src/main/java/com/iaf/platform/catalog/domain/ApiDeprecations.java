package com.iaf.platform.catalog.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.iaf.platform.workflows.domain.WorkflowDefinition.Deprecation;

/**
 * What {@code api.deprecated} turns into on the generated surface: the response headers
 * ({@code Deprecation} as an RFC 9745 date, {@code Sunset} as an RFC 8594 HTTP-date, {@code Link}
 * to the successor), and the note that prefixes the MCP tool description. Pure, no Spring.
 */
public final class ApiDeprecations {

    public static final String DEPRECATION = "Deprecation";
    public static final String SUNSET = "Sunset";
    public static final String LINK = "Link";
    /** IMF-fixdate, as HTTP dates are written. */
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private ApiDeprecations() {
    }

    /** {@code YYYY-MM-DD} (midnight UTC) or an ISO date-time with its offset; null when it is neither. */
    public static Instant instant(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        String d = date.trim();
        try {
            return d.length() == 10 ? LocalDate.parse(d).atStartOfDay(ZoneOffset.UTC).toInstant() : OffsetDateTime.parse(d).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The headers of every answer of a deprecated operation. {@code params} fill the path
     * parameters of a replacement path, so the link points at the successor of this very call.
     */
    public static Map<String, String> headers(Deprecation d, Map<String, String> params) {
        Map<String, String> h = new LinkedHashMap<>();
        if (d == null) {
            return h;
        }
        Instant since = instant(d.since());
        h.put(DEPRECATION, since == null ? "@" + 0 : "@" + since.getEpochSecond());
        Instant sunset = instant(d.sunset());
        if (sunset != null) {
            h.put(SUNSET, HTTP_DATE.format(sunset));
        }
        if (d.replacement() != null && !d.replacement().isBlank()) {
            h.put(LINK, "<" + expand(d.replacement().trim(), params) + ">; rel=\"successor-version\"");
        }
        return h;
    }

    /** {@code Deprecated: <why>. <description>}: the message when there is one, else the sunset and the replacement. */
    public static String describe(String description, Deprecation d) {
        if (d == null) {
            return description;
        }
        String note;
        if (d.message() != null && !d.message().isBlank()) {
            note = d.message().trim();
        } else {
            note = (d.sunset() == null ? "it will be removed" : "it will be removed on " + d.sunset().trim())
                    + (d.replacement() == null || d.replacement().isBlank() ? "" : "; use " + d.replacement().trim() + " instead");
        }
        if (!note.endsWith(".")) {
            note = note + ".";
        }
        return "Deprecated: " + note + (description == null || description.isBlank() ? "" : " " + description);
    }

    private static String expand(String template, Map<String, String> params) {
        String out = template;
        if (params != null) {
            for (var e : params.entrySet()) {
                String value = e.getValue() == null ? "" : URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8).replace("+", "%20");
                out = out.replace("{" + e.getKey() + "}", value);
            }
        }
        return out;
    }
}

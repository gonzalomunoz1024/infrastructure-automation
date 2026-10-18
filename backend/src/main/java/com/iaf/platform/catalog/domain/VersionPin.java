package com.iaf.platform.catalog.domain;

import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pinning a call to one version of a workflow. On the generated REST routes (batches and
 * previews too) the version comes from {@code X-Workflow-Version} or {@code ?version=}; on MCP
 * tools/call from the {@code version} argument. A pinned call runs exactly that version, published
 * or retired, and skips the release router's canary split.
 */
public final class VersionPin {

    public static final String HEADER = "X-Workflow-Version";
    public static final String PARAM = "version";
    public static final int MAX_LENGTH = 64;

    private static final Pattern FORMAT = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z.+-]*$");

    private VersionPin() {
    }

    /**
     * The version a REST call pins: the header, else the query parameter; null when neither is
     * given. 400 when both are given and name different versions, or when one is not a version.
     */
    public static String of(String header, String query) {
        String h = clean(header, HEADER);
        String q = clean(query, "?" + PARAM + "=");
        if (h != null && q != null && !h.equals(q)) {
            throw new IllegalArgumentException(HEADER + " " + h + " and ?" + PARAM + "=" + q + " name different versions");
        }
        return h != null ? h : q;
    }

    /** {@code ?version=} pins the call, except on a read whose input has a field of that name: there it is input, and the header pins. */
    public static boolean queryPins(String method, JsonNode inputSchema) {
        return !"GET".equalsIgnoreCase(method) || inputSchema == null || !inputSchema.path("properties").has(PARAM);
    }

    /** A version as written, trimmed; null when blank; 400 when it is not a version like {@code 1.2.0}. */
    public static String clean(String value, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > MAX_LENGTH || !FORMAT.matcher(v).matches()) {
            throw new IllegalArgumentException(what + " is a workflow version like 1.2.0");
        }
        return v;
    }
}

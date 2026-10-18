package com.iaf.platform.catalog.domain;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The first path segments under {@code /api/v<n>/} that belong to the platform (docs/api.md). A
 * generated route never shadows one of them, whatever a workflow file says and whichever API
 * version it lives under: the gateway skips such a path and the checks report it
 * ({@code api.reservedPath}).
 */
public final class ReservedNouns {

    public static final Set<String> NOUNS = Set.of(
            "workflows", "jobs", "approvals", "providers", "releases", "audit", "resources", "resource-types",
            "credentials", "registry", "catalog", "assistant", "triggers", "change-requests", "templates",
            "service-accounts", "me", "mcp", "openapi.json", "openapi", "batches", "evidence", "ws", "actuator", "notifications");

    /** {@code /api/v1/}, {@code /api/v2/}, ...: the prefixes a generated route may live under. */
    public static final Pattern VERSION_PREFIX = Pattern.compile("^/api/v[1-9][0-9]*/");

    private ReservedNouns() {
    }

    /** The first segment after {@code /api/v<n>/}, cut at the next {@code /}, {@code :} or {@code ?}; null when the path is elsewhere. */
    public static String firstSegment(String path) {
        if (path == null) {
            return null;
        }
        Matcher m = VERSION_PREFIX.matcher(path);
        if (!m.find()) {
            return null;
        }
        String rest = path.substring(m.end());
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == ':' || c == '?') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end);
    }

    public static boolean isReserved(String path) {
        String first = firstSegment(path);
        return first != null && NOUNS.contains(first);
    }

    /** A path the gateway may serve: under {@code /api/v<n>/}, with a non-empty, non-reserved first segment. */
    public static boolean isGenerated(String path) {
        String first = firstSegment(path);
        return first != null && !first.isEmpty() && !NOUNS.contains(first);
    }
}

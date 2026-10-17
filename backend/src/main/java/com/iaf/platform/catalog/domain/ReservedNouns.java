package com.iaf.platform.catalog.domain;

import java.util.Set;

/**
 * The first path segments under {@code /api/v1/} that belong to the platform (docs/api.md). A
 * generated route never shadows one of them, whatever a workflow file says: the gateway skips
 * such a path and the checks report it ({@code api.reservedPath}).
 */
public final class ReservedNouns {

    public static final Set<String> NOUNS = Set.of(
            "workflows", "jobs", "approvals", "providers", "releases", "audit", "resources", "resource-types",
            "credentials", "registry", "catalog", "assistant", "triggers", "change-requests", "templates",
            "service-accounts", "me", "mcp", "openapi.json", "openapi", "batches", "evidence", "ws", "actuator");

    private ReservedNouns() {
    }

    /** The first segment after {@code /api/v1/}, cut at the next {@code /}, {@code :} or {@code ?}; null when the path is elsewhere. */
    public static String firstSegment(String path) {
        if (path == null || !path.startsWith(PlatformInfo.API_PREFIX + "/")) {
            return null;
        }
        String rest = path.substring(PlatformInfo.API_PREFIX.length() + 1);
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

    /** A path the gateway may serve: under {@code /api/v1/}, with a non-empty, non-reserved first segment. */
    public static boolean isGenerated(String path) {
        String first = firstSegment(path);
        return first != null && !first.isEmpty() && !NOUNS.contains(first);
    }
}

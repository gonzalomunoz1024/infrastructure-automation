package com.iaf.console.bff.iam.application;

import java.util.List;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * The IAM collections served under {@code /console/v1/iam/}, and which paths and methods of
 * decision 34's collections exist: the account's guardrails are read only, the policy simulator
 * takes a POST only, access reviews and elevations have their own sub-paths and actions, and the
 * quota and elevation rules (not account-scoped) are read only. A path of these collections that
 * the identity service does not serve is a 404 here, a method it does not take a 405; the phase 1
 * collections pass through as they always did.
 */
public final class IamPaths {

    /** Every collection the IAM pass-through serves. */
    public static final Set<String> COLLECTIONS = Set.of("users", "groups", "roles", "policies", "access-keys", "quotas", "quota-requests", "guardrails",
            "policy-simulations", "access-reviews", "elevations", "quota-rules", "elevation-rules");

    /** The collections that are the organization's, not an account's: {@code /v1/<collection>} at the identity service. */
    public static final Set<String> GLOBAL = Set.of("quota-rules", "elevation-rules");

    private static final Set<HttpMethod> GET = Set.of(HttpMethod.GET);
    private static final Set<HttpMethod> POST = Set.of(HttpMethod.POST);
    private static final Set<HttpMethod> GET_POST = Set.of(HttpMethod.GET, HttpMethod.POST);

    private IamPaths() {
    }

    /** The collection a path names: its first segment without an {@code :action}. */
    public static String collectionOf(String rest) {
        int end = rest.length();
        for (char c : new char[] {'/', '?', ':'}) {
            int at = rest.indexOf(c);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        return rest.substring(0, end);
    }

    /** Whether a collection is the organization's rather than the session account's. */
    public static boolean global(String rest) {
        return GLOBAL.contains(collectionOf(rest));
    }

    /**
     * Null when the call is one the identity service serves (or one of a phase 1 collection, which
     * passes through unchecked), else the status to answer: 404 for an unknown path, 405 for a
     * method the path does not take.
     */
    public static HttpStatus refusal(HttpMethod method, String rest) {
        String collection = collectionOf(rest);
        if (!COLLECTIONS.contains(collection)) {
            return HttpStatus.NOT_FOUND;
        }
        List<String> segments = List.of(rest.split("/", -1));
        Set<HttpMethod> allowed = switch (collection) {
            case "guardrails", "quota-rules", "elevation-rules" -> plain(segments, collection) ? GET : null;
            case "policy-simulations" -> plain(segments, collection) ? POST : null;
            case "access-reviews" -> accessReviews(segments);
            case "elevations" -> elevations(segments);
            default -> Set.of(method);
        };
        if (allowed == null) {
            return HttpStatus.NOT_FOUND;
        }
        return allowed.contains(method) ? null : HttpStatus.METHOD_NOT_ALLOWED;
    }

    private static boolean plain(List<String> segments, String collection) {
        return segments.size() == 1 && segments.get(0).equals(collection);
    }

    private static Set<HttpMethod> accessReviews(List<String> s) {
        if (plain(s, "access-reviews")) {
            return GET_POST;
        }
        if (!s.get(0).equals("access-reviews") || s.size() < 2 || s.get(1).isEmpty()) {
            return null;
        }
        String id = s.get(1);
        if (s.size() == 2) {
            if (id.endsWith(":cancel") && id.length() > ":cancel".length()) {
                return POST;
            }
            return id.contains(":") ? null : GET;
        }
        if (id.contains(":") || !s.get(2).equals("items")) {
            return null;
        }
        if (s.size() == 3) {
            return GET;
        }
        if (s.size() == 4 && s.get(3).endsWith(":decide") && s.get(3).length() > ":decide".length()) {
            return POST;
        }
        return null;
    }

    private static Set<HttpMethod> elevations(List<String> s) {
        if (plain(s, "elevations")) {
            return GET_POST;
        }
        if (!s.get(0).equals("elevations") || s.size() != 2 || s.get(1).isEmpty()) {
            return null;
        }
        String id = s.get(1);
        if (!id.contains(":")) {
            return GET;
        }
        String action = id.substring(id.indexOf(':') + 1);
        boolean named = id.indexOf(':') > 0;
        return named && ("decide".equals(action) || "revoke".equals(action)) ? POST : null;
    }
}

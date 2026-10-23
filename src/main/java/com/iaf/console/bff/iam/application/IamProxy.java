package com.iaf.console.bff.iam.application;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.identity.application.IdentityClient;
import com.iaf.console.bff.session.application.MembershipChanges;
import com.iaf.console.bff.session.domain.RoleConventions;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

import reactor.core.publisher.Mono;

/**
 * The account-scoped identity resources served through the BFF: {@code /console/v1/iam/<rest>}
 * and {@code /console/v1/access-requests...} become {@code /v1/accounts/{account}/<rest>} at the
 * identity service (the quota and elevation rules, which are the organization's, {@code /v1/<rest>}),
 * called with an internal token for the session's user, so the identity service's own policies
 * decide. The status, ETag and body come back as they were; a 403 without a {@code denied} block
 * gets one, a {@code denied} block the identity service sent names what refused it as
 * {@code refusedBy} (its {@code scope}), and a successful write drops the account's cached
 * decisions and makes the account's sessions read their memberships again.
 */
public class IamProxy {

    static final Set<String> FORWARDED = Set.of("if-match", "if-none-match", "idempotency-key", "content-type", "accept");
    static final Set<HttpMethod> WRITES = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private final IdentityClient identity;
    private final Access access;
    private final MembershipChanges changes;
    private final ObjectMapper json;

    public IamProxy(IdentityClient identity, Access access, MembershipChanges changes, ObjectMapper json) {
        this.identity = identity;
        this.access = access;
        this.changes = changes;
        this.json = json;
    }

    public Mono<ProxiedResponse> forward(Session session, HttpMethod method, String rest, String rawQuery, HttpHeaders inbound, byte[] body) {
        // every segment is checked and encoded again, whether the path is the organization's or the account's
        String encoded = encodedPath(rest);
        String path = IamPaths.global(rest) ? "/v1/" + encoded
                : "/v1/accounts/" + UriUtils.encodePathSegment(session.accountId(), StandardCharsets.UTF_8) + "/" + encoded;
        WebClient.RequestBodySpec request = identity.asUser(method, session).uri(uri(identity, path, rawQuery));
        return exchange(identity, request, inbound, Map.of(), body, bytes -> withDenial(bytes, session, method, rest, body))
                .doOnNext(response -> {
                    if (response.status().is2xxSuccessful() && WRITES.contains(method)) {
                        access.invalidate(session.accountId());
                        // whoever's memberships the write changed: every session of the account reads them again
                        changes.accountChanged(session.accountId());
                    }
                });
    }

    static URI uri(IdentityClient identity, String path, String rawQuery) {
        String base = identity.url().endsWith("/") ? identity.url().substring(0, identity.url().length() - 1) : identity.url();
        return URI.create(base + path + (rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery));
    }

    /** Sends a request with the forwarded headers and the body, and answers what came back; a 403's body goes through {@code onForbidden}. */
    static Mono<ProxiedResponse> exchange(IdentityClient identity, WebClient.RequestBodySpec request, HttpHeaders inbound, Map<String, String> extra,
            byte[] body, UnaryOperator<byte[]> onForbidden) {
        inbound.forEach((name, values) -> {
            if (FORWARDED.contains(name.toLowerCase()) && !values.isEmpty()) {
                request.header(name, values.get(0));
            }
        });
        extra.forEach(request::header);
        if (body != null && body.length > 0) {
            request.bodyValue(body);
        }
        return request.exchangeToMono(response -> response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).map(bytes -> {
            MediaType contentType = response.headers().contentType().orElse(MediaType.APPLICATION_JSON);
            String etag = response.headers().header(HttpHeaders.ETAG).stream().findFirst().orElse(null);
            byte[] answered = bytes;
            if (response.statusCode().value() == HttpStatus.FORBIDDEN.value()) {
                answered = onForbidden.apply(bytes);
                contentType = MediaType.APPLICATION_JSON;
            }
            return new ProxiedResponse(response.statusCode(), contentType, etag, answered);
        })).timeout(identity.timeout());
    }

    /**
     * The path after the account, rebuilt from its segments, each decoded once and encoded again.
     * A segment that is empty, {@code .} or {@code ..}, or that holds an encoded dot or slash or a
     * backslash (in any case), is a 400: nothing that could climb out of the account's path
     * reaches the identity service.
     */
    static String encodedPath(String rest) {
        String path = rest == null ? "" : rest;
        List<String> encoded = new ArrayList<>();
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String raw = segments[i];
            String lower = raw.toLowerCase(Locale.ROOT);
            if (raw.isEmpty() || ".".equals(raw) || "..".equals(raw) || lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")
                    || raw.contains("\\")) {
                throw badSegment(i);
            }
            String decoded;
            try {
                decoded = UriUtils.decode(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw badSegment(i);
            }
            encoded.add(UriUtils.encodePathSegment(decoded, StandardCharsets.UTF_8));
        }
        return String.join("/", encoded);
    }

    private static InvalidInputException badSegment(int index) {
        return new InvalidInputException("the IAM path has a segment that is empty, a dot segment, or an encoded dot, slash or backslash",
                List.of(Problem.error("path.segment", "/path/" + index, "not a name")));
    }

    /**
     * A 403 body with a {@code denied} block: the identity service's, its {@code scope} (policy,
     * boundary or guardrail) named {@code refusedBy}, never requestable past a boundary or a
     * guardrail; or one made here when it sent none.
     */
    byte[] withDenial(byte[] body, Session session, HttpMethod method, String rest, byte[] requestBody) {
        try {
            JsonNode parsed = body.length == 0 ? json.createObjectNode() : json.readTree(body);
            if (parsed != null && parsed.isObject() && parsed.has("denied")) {
                return normalized((ObjectNode) parsed) ? json.writeValueAsBytes(parsed) : body;
            }
            ObjectNode out = parsed != null && parsed.isObject() ? (ObjectNode) parsed : json.createObjectNode();
            String simulated = principalOf(requestBody);
            String action = actionOf(method, rest, simulated);
            String role = RoleConventions.strongest(session.currentAccount().roles());
            String reason = out.path("error").asText("No statement allows " + action + " for role " + role);
            if (!out.has("error")) {
                out.put("error", reason);
            }
            Denial denial = Denial.byPolicy(action, resourceOf(session.accountId(), rest, simulated), reason, role, null, null);
            out.set("denied", json.valueToTree(denial));
            return json.writeValueAsBytes(out);
        } catch (IOException e) {
            return ("{\"error\":\"forbidden\"}").getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Rewrites an identity service {@code denied} block in the BFF's names: its {@code scope} of
     * policy, boundary or guardrail becomes {@code refusedBy} (the BFF's {@code scope} is a
     * platform scope), and a boundary's or a guardrail's refusal is not requestable and suggests no
     * role. True when anything changed.
     */
    static boolean normalized(ObjectNode body) {
        JsonNode raw = body.get("denied");
        if (raw == null || !raw.isObject()) {
            return false;
        }
        ObjectNode denied = (ObjectNode) raw;
        boolean changed = false;
        String scope = denied.path("scope").asText(null);
        if (Denial.isRefusedBy(scope)) {
            denied.remove("scope");
            if (!denied.hasNonNull("refusedBy")) {
                denied.put("refusedBy", scope);
            }
            changed = true;
        }
        String by = denied.path("refusedBy").asText(null);
        if (Denial.BY_BOUNDARY.equals(by) || Denial.BY_GUARDRAIL.equals(by)) {
            if (denied.path("requestable").asBoolean(true)) {
                denied.put("requestable", false);
                changed = true;
            }
            if (denied.has("suggestedRole")) {
                denied.remove("suggestedRole");
                changed = true;
            }
        }
        return changed;
    }

    /** What a policy simulation asked about: {@code user/<id>}, {@code role/<name>} or {@code group/*}; null for any other body. */
    private String principalOf(byte[] requestBody) {
        if (requestBody == null || requestBody.length == 0) {
            return null;
        }
        try {
            JsonNode principal = json.readTree(requestBody).path("principal");
            if (principal.hasNonNull("userId")) {
                return "user/" + principal.path("userId").asText();
            }
            if (principal.hasNonNull("role")) {
                return "role/" + principal.path("role").asText();
            }
            if (principal.has("groups")) {
                return "group/*";
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    static String actionOf(HttpMethod method, String rest) {
        return actionOf(method, rest, null);
    }

    /**
     * The action a refused call was, in the identity service's names: {@code quota:<verb>} for
     * quotas and their requests ({@code quota:set} for a write or a withdrawal,
     * {@code quota:request-increase}), {@code access-request:<verb>}, {@code access-review:<verb>}
     * (reading a review's items is {@code get}), {@code elevation:<verb>}, the policy simulator's
     * {@code iam:get} (of a person or a role) or {@code iam:list} (of groups), else {@code iam:<verb>}.
     */
    static String actionOf(HttpMethod method, String rest, String simulated) {
        if (rest.endsWith(":request-increase")) {
            return "quota:request-increase";
        }
        String verb = verbOf(method, rest);
        return switch (IamPaths.collectionOf(rest)) {
            case "quotas", "quota-requests" -> "quota:" + ("update".equals(verb) || "delete".equals(verb) ? "set" : verb);
            case "access-requests" -> "access-request:" + verb;
            case "access-reviews" -> "access-review:" + verb;
            case "elevations" -> "elevation:" + verb;
            case "policy-simulations" -> "group/*".equals(simulated) ? "iam:list" : "iam:get";
            default -> "iam:" + verb;
        };
    }

    static String resourceOf(String accountId, String rest) {
        return resourceOf(accountId, rest, null);
    }

    /**
     * The wfrn of what a refused call named, as the identity service names IAM things:
     * {@code wfrn:wf:iam::<account>:<kind>/<id or *>}, the id being the record's (an access review's
     * for its items); a simulation names the principal it asked about.
     */
    static String resourceOf(String accountId, String rest, String simulated) {
        String path = rest.contains("?") ? rest.substring(0, rest.indexOf('?')) : rest;
        String collection = IamPaths.collectionOf(path);
        if ("policy-simulations".equals(collection)) {
            return "wfrn:wf:iam::" + accountId + ":" + (simulated == null ? "user/*" : simulated);
        }
        String kind = switch (collection) {
            case "policies" -> "policy";
            case "quotas" -> "quota";
            default -> collection.endsWith("s") ? collection.substring(0, collection.length() - 1) : collection;
        };
        int slash = path.indexOf('/');
        String id = "*";
        if (slash >= 0 && slash < path.length() - 1) {
            String after = path.substring(slash + 1);
            int next = after.indexOf('/');
            id = (next < 0 ? after : after.substring(0, next)).replaceAll(":[a-z-]+$", "");
        }
        return "wfrn:wf:iam::" + accountId + ":" + kind + "/" + id;
    }

    static String verbOf(HttpMethod method, String rest) {
        for (String action : new String[] {"rotate", "decide", "cancel", "revoke"}) {
            if (rest.endsWith(":" + action)) {
                return action;
            }
        }
        if (rest.endsWith(":request-increase")) {
            return "update";
        }
        if (method == HttpMethod.POST) {
            return "create";
        }
        if (method == HttpMethod.PATCH || method == HttpMethod.PUT) {
            return "update";
        }
        if (method == HttpMethod.DELETE) {
            return "delete";
        }
        return rest.contains("/") ? "get" : "list";
    }
}

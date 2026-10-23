package com.iaf.console.bff.iam.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.identity.application.IdentityClient;
import com.iaf.console.bff.session.domain.RoleConventions;
import com.iaf.console.bff.session.domain.Session;

import reactor.core.publisher.Mono;

/**
 * The account-scoped identity resources served through the BFF: {@code /console/v1/iam/<rest>}
 * and {@code /console/v1/access-requests...} become {@code /v1/accounts/{account}/<rest>} at the
 * identity service, called with an internal token for the session's user, so the identity
 * service's own policies decide. The status, ETag and body come back as they were; a 403 without
 * a {@code denied} block gets one, and a successful write drops the account's cached decisions.
 */
public class IamProxy {

    private static final Set<String> FORWARDED = Set.of("if-match", "if-none-match", "idempotency-key", "content-type", "accept");
    private static final Set<HttpMethod> WRITES = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private final IdentityClient identity;
    private final Access access;
    private final ObjectMapper json;

    public IamProxy(IdentityClient identity, Access access, ObjectMapper json) {
        this.identity = identity;
        this.access = access;
        this.json = json;
    }

    public Mono<ProxiedResponse> forward(Session session, HttpMethod method, String rest, String rawQuery, HttpHeaders inbound, byte[] body) {
        String base = identity.url().endsWith("/") ? identity.url().substring(0, identity.url().length() - 1) : identity.url();
        String path = "/v1/accounts/" + session.accountId() + "/" + rest;
        URI uri = URI.create(base + path + (rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery));
        WebClient.RequestBodySpec request = identity.asUser(method, session).uri(uri);
        inbound.forEach((name, values) -> {
            if (FORWARDED.contains(name.toLowerCase()) && !values.isEmpty()) {
                request.header(name, values.get(0));
            }
        });
        if (body != null && body.length > 0) {
            request.bodyValue(body);
        }
        return request.exchangeToMono(response -> response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).map(bytes -> {
            MediaType contentType = response.headers().contentType().orElse(MediaType.APPLICATION_JSON);
            String etag = response.headers().header(HttpHeaders.ETAG).stream().findFirst().orElse(null);
            byte[] answered = bytes;
            if (response.statusCode().value() == HttpStatus.FORBIDDEN.value()) {
                answered = withDenial(bytes, session, method, rest);
                contentType = MediaType.APPLICATION_JSON;
            }
            if (response.statusCode().is2xxSuccessful() && WRITES.contains(method)) {
                access.invalidate(session.accountId());
            }
            return new ProxiedResponse(response.statusCode(), contentType, etag, answered);
        })).timeout(identity.timeout());
    }

    /** A 403 body with a {@code denied} block, added when the identity service sent none. */
    byte[] withDenial(byte[] body, Session session, HttpMethod method, String rest) {
        try {
            JsonNode parsed = body.length == 0 ? json.createObjectNode() : json.readTree(body);
            if (parsed.isObject() && parsed.has("denied")) {
                return body;
            }
            ObjectNode out = parsed.isObject() ? (ObjectNode) parsed : json.createObjectNode();
            String action = actionOf(method, rest);
            String role = RoleConventions.strongest(session.currentAccount().roles());
            String reason = out.path("error").asText("No statement allows " + action + " for role " + role);
            if (!out.has("error")) {
                out.put("error", reason);
            }
            Denial denial = Denial.byPolicy(action, resourceOf(session.accountId(), rest), reason, role, null, null);
            out.set("denied", json.valueToTree(denial));
            return json.writeValueAsBytes(out);
        } catch (java.io.IOException e) {
            return ("{\"error\":\"forbidden\"}").getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * The action a refused call was, in the identity service's names: {@code quota:<verb>} for
     * quotas and their requests ({@code quota:set} for a write or a withdrawal,
     * {@code quota:request-increase}), {@code access-request:<verb>}, else {@code iam:<verb>}.
     */
    static String actionOf(HttpMethod method, String rest) {
        if (rest.endsWith(":request-increase")) {
            return "quota:request-increase";
        }
        String verb = verbOf(method, rest);
        return switch (collectionOf(rest)) {
            case "quotas", "quota-requests" -> "quota:" + ("update".equals(verb) || "delete".equals(verb) ? "set" : verb);
            case "access-requests" -> "access-request:" + verb;
            default -> "iam:" + verb;
        };
    }

    /** The wfrn of what a refused call named, as the identity service names IAM things: {@code wfrn:wf:iam::<account>:<kind>/<id or *>}. */
    static String resourceOf(String accountId, String rest) {
        String path = rest.contains("?") ? rest.substring(0, rest.indexOf('?')) : rest;
        int slash = path.indexOf('/');
        String collection = collectionOf(path);
        String kind = switch (collection) {
            case "policies" -> "policy";
            case "quotas" -> "quota";
            default -> collection.endsWith("s") ? collection.substring(0, collection.length() - 1) : collection;
        };
        String id = slash < 0 || slash == path.length() - 1 ? "*" : path.substring(slash + 1).replaceAll(":[a-z-]+$", "");
        return "wfrn:wf:iam::" + accountId + ":" + kind + "/" + id;
    }

    private static String collectionOf(String rest) {
        int end = rest.length();
        for (char c : new char[] {'/', '?', ':'}) {
            int at = rest.indexOf(c);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        return rest.substring(0, end);
    }

    static String verbOf(HttpMethod method, String rest) {
        if (rest.endsWith(":rotate")) {
            return "rotate";
        }
        if (rest.endsWith(":decide")) {
            return "decide";
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

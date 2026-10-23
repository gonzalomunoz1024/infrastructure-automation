package com.iaf.console.drift.drift.adapters.state;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.FindingField;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Observation;
import com.iaf.console.drift.drift.domain.Resolution;
import com.iaf.console.drift.drift.domain.Severity;
import com.iaf.console.drift.drift.domain.StateResource;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * The state service over HTTP ({@code contracts/openapi/console-state-service.yaml}), called as the
 * {@code console-drift-service} principal: the given credentials put a service token the BFF
 * issued on each request (hub decision 23), or the dev header where the state service accepts it.
 * Every call ends after the timeout: the answer must start within it, and its body may not stall
 * for longer between reads. A wfrn travels percent-encoded in the path. An error answer
 * becomes a {@link StateError} with the state's own error code and detail.
 */
public final class HttpStateService implements StateServicePort {

    public static final String SERVICE = "console-drift-service";
    public static final String SERVICE_HEADER = "X-Console-Service";
    public static final String REQUEST_ID_HEADER = "X-Console-Request-Id";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final WebClient web;
    private final String baseUrl;

    public HttpStateService(WebClient.Builder builder, String baseUrl, ExchangeFilterFunction credentials, Duration timeout) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = base;
        this.web = builder.clone().clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout)))
                .baseUrl(base).defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, SERVICE)
                .filter(credentials)
                .filter((request, next) -> next.exchange(request).timeout(timeout))
                .build();
    }

    @Override
    public Mono<Page<StateResource>> listResources(String accountId, Instant updatedAfter, String after, int limit) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl + "/v1/resources")
                .queryParam("accountId", accountId)
                .queryParam("sort", "updatedAt")
                .queryParam("limit", limit);
        if (updatedAfter != null) {
            uri.queryParam("updatedAfter", updatedAfter.toString());
        }
        if (after != null && !after.isBlank()) {
            uri.queryParam("after", after);
        }
        return web.get().uri(uri.build().toUri()).exchangeToMono(r -> body(r, JsonNode.class)).map(node -> {
            List<StateResource> items = new ArrayList<>();
            for (JsonNode item : node.path("items")) {
                items.add(Json.read(item, StateResource.class));
            }
            String next = node.hasNonNull("next") ? node.get("next").asText() : null;
            return new Page<>(items, next);
        });
    }

    @Override
    public Mono<StateResource> getResource(String wfrn) {
        return web.get().uri(resource(wfrn, "")).exchangeToMono(r -> {
            if (r.statusCode().equals(HttpStatus.GONE)) {
                // the tombstone: the resource is gone; what the sync needs is the name and that it is deleted
                return r.bodyToMono(JsonNode.class).defaultIfEmpty(Json.object()).map(t -> tombstone(wfrn, t));
            }
            return body(r, StateResource.class);
        });
    }

    @Override
    public Mono<StateResource> recordObservation(String wfrn, Observation observation, String actor) {
        ObjectNode body = Json.object();
        body.put("status", observation.status());
        if (observation.observed() != null && !observation.observed().isNull()) {
            body.set("observed", observation.observed());
        }
        body.put("observedAt", observation.at().toString());
        if (observation.provider() != null) {
            body.put("provider", observation.provider());
        }
        if (observation.action() != null) {
            body.put("action", observation.action());
        }
        body.put("actor", actor);
        return command(wfrn, ":record-observation", body, null, null);
    }

    @Override
    public Mono<StateResource> recordDrift(String wfrn, String findingId, Severity severity, List<FindingField> fields, String actor) {
        ObjectNode body = Json.object();
        body.put("findingId", findingId);
        body.put("severity", severity.id());
        ArrayNode list = body.putArray("fields");
        for (FindingField f : fields) {
            ObjectNode field = list.addObject();
            field.put("path", f.path());
            if (f.expected() != null) {
                field.set("expected", f.expected());
            }
            if (f.observed() != null) {
                field.set("observed", f.observed());
            }
        }
        body.put("actor", actor);
        return command(wfrn, ":record-drift", body, null, null);
    }

    @Override
    public Mono<StateResource> resolveDrift(String wfrn, String findingId, Resolution resolution, String actor) {
        ObjectNode body = Json.object();
        body.put("findingId", findingId);
        body.put("resolution", resolution.id());
        body.put("actor", actor);
        return command(wfrn, ":resolve-drift", body, null, null);
    }

    @Override
    public Mono<StateResource> requestUpdate(String wfrn, String workflow, String workflowVersion, ObjectNode changes, String actor, String requestId) {
        ObjectNode body = Json.object();
        body.put("workflow", workflow);
        if (workflowVersion != null) {
            body.put("workflowVersion", workflowVersion);
        }
        body.set("changes", changes);
        body.put("actor", actor);
        return command(wfrn, ":request-update", body, requestId, requestId == null ? null : "drift-revert:" + requestId);
    }

    @Override
    public Mono<StateResource> requestDelete(String wfrn, String workflow, String reason, String actor, String requestId) {
        ObjectNode body = Json.object();
        body.put("workflow", workflow);
        if (reason != null) {
            body.put("reason", reason);
        }
        body.put("actor", actor);
        return command(wfrn, ":request-delete", body, requestId, requestId == null ? null : "drift-delete:" + requestId);
    }

    @Override
    public Mono<StateResource> importResource(ImportCommand c) {
        ObjectNode body = Json.object();
        body.put("type", c.type());
        body.put("region", c.region());
        body.put("wfrn", c.wfrn());
        // the platform's registry does not know a discovered object; the state contract requires a
        // registryId, and the provider's id is the only id the object has (source discover says so)
        body.put("registryId", c.providerId());
        body.put("providerId", c.providerId());
        if (c.name() != null) {
            body.put("name", c.name());
        }
        body.set("attributes", c.attributes() == null ? Json.object() : c.attributes());
        body.put("source", "discover");
        body.put("actor", c.actor());
        WebClient.RequestBodySpec request = web.post().uri(URI.create(baseUrl + "/v1/resources:import")).contentType(MediaType.APPLICATION_JSON);
        if (c.requestId() != null) {
            request.header(REQUEST_ID_HEADER, c.requestId()).header(IDEMPOTENCY_HEADER, "drift-import:" + c.requestId());
        }
        return request.bodyValue(body).exchangeToMono(r -> body(r, StateResource.class));
    }

    private Mono<StateResource> command(String wfrn, String suffix, ObjectNode body, String requestId, String idempotencyKey) {
        WebClient.RequestBodySpec request = web.post().uri(resource(wfrn, suffix)).contentType(MediaType.APPLICATION_JSON);
        if (requestId != null) {
            request.header(REQUEST_ID_HEADER, requestId);
        }
        if (idempotencyKey != null) {
            request.header(IDEMPOTENCY_HEADER, idempotencyKey);
        }
        return request.bodyValue(body).exchangeToMono(r -> body(r, StateResource.class));
    }

    private URI resource(String wfrn, String suffix) {
        return URI.create(baseUrl + "/v1/wfrn/" + URLEncoder.encode(wfrn, StandardCharsets.UTF_8) + suffix);
    }

    private static <T> Mono<T> body(ClientResponse r, Class<T> type) {
        if (r.statusCode().is2xxSuccessful()) {
            return r.bodyToMono(type);
        }
        return r.bodyToMono(JsonNode.class).defaultIfEmpty(Json.object()).flatMap(node -> {
            String error = node.hasNonNull("error") ? node.get("error").asText() : "http " + r.statusCode().value();
            return Mono.error(new StateError(r.statusCode().value(), error, node.get("detail")));
        });
    }

    static StateResource tombstone(String wfrn, JsonNode t) {
        ObjectNode node = Json.object();
        node.put("wfrn", t.hasNonNull("wfrn") ? t.get("wfrn").asText() : wfrn);
        node.put("state", "deleted");
        node.put("deletedAt", t.hasNonNull("deletedAt") ? t.get("deletedAt").asText() : Instant.EPOCH.toString());
        if (t.hasNonNull("lastVersion")) {
            node.put("version", t.get("lastVersion").asLong());
        }
        return Json.read(node, StateResource.class);
    }
}

package com.iaf.console.bff.drift.adapters.outbound.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.console.bff.drift.application.DriftConfiguration;
import com.iaf.console.bff.drift.application.DriftProperties;
import com.iaf.console.bff.drift.domain.Alarm;
import com.iaf.console.bff.drift.domain.AlarmInput;
import com.iaf.console.bff.drift.domain.DiscoveryRun;
import com.iaf.console.bff.drift.domain.DriftCaller;
import com.iaf.console.bff.drift.domain.DriftCommands;
import com.iaf.console.bff.drift.domain.DriftConflictException;
import com.iaf.console.bff.drift.domain.DriftGatewayException;
import com.iaf.console.bff.drift.domain.DriftQueries;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.bff.drift.domain.Finding;
import com.iaf.console.bff.drift.domain.FindingStats;
import com.iaf.console.bff.drift.domain.FindingsSummary;
import com.iaf.console.bff.drift.domain.Recommendation;
import com.iaf.console.bff.drift.domain.RecommendationsSummary;
import com.iaf.console.bff.drift.domain.ResourceMetrics;
import com.iaf.console.bff.drift.domain.UnmanagedObject;
import com.iaf.console.bff.drift.ports.outbound.DriftPort;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.domain.InternalToken;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.errors.TooManyRequestsException;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The drift service over HTTP, as its contract says: an internal JWT on every call (the person's
 * in their account, or the BFF's own for the console-internal principal), the scope's account as
 * {@code accountId} on the reads and its region wherever an operation takes one, every value sent
 * as a strictly encoded URI variable, and the writes' headers ({@code Idempotency-Key},
 * {@code X-Console-Request-Id}, {@code X-On-Behalf-Of}). Its errors become the kit's exceptions,
 * a 409 keeps its code and a 502 its correlation id. With no URL configured it points at the
 * in-process demo stand-in.
 */
@Component
public class HttpDriftPort implements DriftPort {

    static final String ON_BEHALF_OF = "X-On-Behalf-Of";
    static final String REQUEST_ID = "X-Console-Request-Id";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final WebClient.Builder builder;
    private final Supplier<String> baseUrl;
    private final Trust trust;
    private final DriftProperties properties;
    private final ObjectMapper json;
    private volatile WebClient client;

    @Autowired
    public HttpDriftPort(WebClient.Builder builder, Trust trust, DriftProperties properties, ObjectMapper json, Environment environment) {
        this(builder, () -> {
            if (properties.url() != null && !properties.url().isBlank()) {
                return properties.url().replaceAll("/+$", "");
            }
            String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "18081"));
            return "http://127.0.0.1:" + port + DriftConfiguration.DEMO_PATH;
        }, trust, properties, json);
    }

    HttpDriftPort(WebClient.Builder builder, Supplier<String> baseUrl, Trust trust, DriftProperties properties, ObjectMapper json) {
        this.builder = builder;
        this.baseUrl = baseUrl;
        this.trust = trust;
        this.properties = properties;
        this.json = json;
    }

    /** The drift service's URL, resolved on first use (the demo stand-in needs the server's port). */
    public String url() {
        return baseUrl.get();
    }

    // ---- findings

    @Override
    public Mono<Page<Finding>> listFindings(AccountScope scope, DriftCaller caller, DriftQueries.Findings query) {
        return get(scope, caller, b -> {
            Query q = new Query(b, "/v1/findings").account(scope).region(scope);
            q.param("status", query.status()).param("severity", query.severity()).param("type", query.type()).param("wfrn", query.wfrn())
                    .param("openedAfter", query.openedAfter()).page(query.limit(), query.after());
            return q.build();
        }).map(answer -> page(answer.body(), Finding.class));
    }

    @Override
    public Mono<FindingsSummary> getFindingsSummary(AccountScope scope, DriftCaller caller) {
        return get(scope, caller, b -> new Query(b, "/v1/findings/summary").account(scope).region(scope).build())
                .map(answer -> read(answer.body(), FindingsSummary.class));
    }

    @Override
    public Mono<FindingStats> getFindingStats(AccountScope scope, DriftCaller caller, Instant since, Instant until) {
        return get(scope, caller, b -> new Query(b, "/v1/findings/stats").account(scope).param("since", since).param("until", until).build())
                .map(answer -> read(answer.body(), FindingStats.class));
    }

    @Override
    public Mono<Finding> getFinding(AccountScope scope, DriftCaller caller, String findingId) {
        return get(scope, caller, b -> b.path("/v1/findings/{id}").build(Map.of("id", findingId))).map(answer -> read(answer.body(), Finding.class));
    }

    @Override
    public Mono<DriftResults.RevertStarted> revertFinding(AccountScope scope, DriftCaller caller, String findingId, DriftCommands.Decision decision,
            DriftCommands.Meta meta) {
        return send(HttpMethod.POST, scope, caller, b -> b.path("/v1/findings/{id}:revert").build(Map.of("id", findingId)), decision(decision), meta)
                .map(answer -> read(answer.body(), DriftResults.RevertStarted.class));
    }

    @Override
    public Mono<Finding> acceptFinding(AccountScope scope, DriftCaller caller, String findingId, DriftCommands.Decision decision,
            DriftCommands.Meta meta) {
        return send(HttpMethod.POST, scope, caller, b -> b.path("/v1/findings/{id}:accept").build(Map.of("id", findingId)), decision(decision), meta)
                .map(answer -> read(answer.body(), Finding.class));
    }

    @Override
    public Mono<Finding> ignoreFinding(AccountScope scope, DriftCaller caller, String findingId, DriftCommands.Decision decision,
            DriftCommands.Meta meta) {
        return send(HttpMethod.POST, scope, caller, b -> b.path("/v1/findings/{id}:ignore").build(Map.of("id", findingId)), decision(decision), meta)
                .map(answer -> read(answer.body(), Finding.class));
    }

    // ---- the shadow-infrastructure finder

    @Override
    public Mono<DiscoveryRun> startDiscovery(AccountScope scope, DriftCaller caller, String type, DriftCommands.Meta meta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("region", scope.region());
        return send(HttpMethod.POST, scope, caller, b -> b.path("/v1/discover").build(), body, meta).map(answer -> read(answer.body(), DiscoveryRun.class));
    }

    @Override
    public Mono<DiscoveryRun> getDiscovery(AccountScope scope, DriftCaller caller, String runId) {
        return get(scope, caller, b -> b.path("/v1/discover/{id}").build(Map.of("id", runId))).map(answer -> read(answer.body(), DiscoveryRun.class));
    }

    @Override
    public Mono<Page<UnmanagedObject>> listUnmanaged(AccountScope scope, DriftCaller caller, DriftQueries.Unmanaged query) {
        return get(scope, caller, b -> new Query(b, "/v1/unmanaged").account(scope).region(scope).param("type", query.type())
                .param("owner", query.owner()).page(query.limit(), query.after()).build())
                .map(answer -> page(answer.body(), UnmanagedObject.class));
    }

    @Override
    public Mono<DriftResults.Imported> importUnmanaged(AccountScope scope, DriftCaller caller, DriftCommands.Import command, DriftCommands.Meta meta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", command.type());
        body.put("region", scope.region());
        body.put("providerId", command.providerId());
        putIfSet(body, "name", command.name());
        return send(HttpMethod.POST, scope, caller, b -> b.path("/v1/unmanaged:import").build(), body, meta)
                .map(answer -> read(answer.body(), DriftResults.Imported.class));
    }

    @Override
    public Mono<UnmanagedObject> assignUnmanaged(AccountScope scope, DriftCaller caller, DriftCommands.Assign command, DriftCommands.Meta meta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", command.type());
        body.put("region", scope.region());
        body.put("providerId", command.providerId());
        body.put("accountId", command.accountId());
        putIfSet(body, "note", command.note());
        putIfSet(body, "actor", command.actor());
        return send(HttpMethod.POST, scope, caller, b -> b.path("/v1/unmanaged:assign").build(), body, meta)
                .map(answer -> read(answer.body(), UnmanagedObject.class));
    }

    // ---- monitoring

    @Override
    public Mono<ResourceMetrics> getResourceMetrics(AccountScope scope, DriftCaller caller, DriftQueries.Metrics query) {
        return get(scope, caller, b -> new Query(b, "/v1/metrics").param("wfrn", query.wfrn()).param("from", query.from()).param("to", query.to())
                .param("step", query.step() == null ? null : query.step().toString()).build())
                .map(answer -> read(answer.body(), ResourceMetrics.class));
    }

    @Override
    public Mono<Page<Alarm>> listAlarms(AccountScope scope, DriftCaller caller, DriftQueries.Alarms query) {
        return get(scope, caller, b -> new Query(b, "/v1/alarms").account(scope).param("wfrn", query.wfrn()).param("state", query.state())
                .page(query.limit(), query.after()).build())
                .map(answer -> page(answer.body(), Alarm.class));
    }

    @Override
    public Mono<Alarm> getAlarm(AccountScope scope, DriftCaller caller, String alarmId) {
        return get(scope, caller, b -> b.path("/v1/alarms/{id}").build(Map.of("id", alarmId))).map(answer -> read(answer.body(), Alarm.class));
    }

    @Override
    public Mono<DriftResults.AlarmSaved> putAlarm(AccountScope scope, DriftCaller caller, String alarmId, AlarmInput input, DriftCommands.Meta meta) {
        Map<String, Object> body = new LinkedHashMap<>();
        putIfSet(body, "wfrn", input.wfrn());
        putIfSet(body, "name", input.name());
        putIfSet(body, "metric", input.metric());
        putIfSet(body, "comparison", input.comparison());
        putIfSet(body, "threshold", input.threshold());
        putIfSet(body, "windowMinutes", input.windowMinutes());
        putIfSet(body, "actor", input.actor());
        return send(HttpMethod.PUT, scope, caller, b -> b.path("/v1/alarms/{id}").build(Map.of("id", alarmId)), body, meta)
                .map(answer -> new DriftResults.AlarmSaved(read(answer.body(), Alarm.class), answer.status() == 201));
    }

    @Override
    public Mono<Void> deleteAlarm(AccountScope scope, DriftCaller caller, String alarmId, DriftCommands.Meta meta) {
        return send(HttpMethod.DELETE, scope, caller, b -> b.path("/v1/alarms/{id}").build(Map.of("id", alarmId)), null, meta).then();
    }

    // ---- rightsizing

    @Override
    public Mono<Page<Recommendation>> listRecommendations(AccountScope scope, DriftCaller caller, DriftQueries.Recommendations query) {
        return get(scope, caller, b -> new Query(b, "/v1/recommendations").account(scope).param("type", query.type()).param("kind", query.kind())
                .param("status", query.status()).param("wfrn", query.wfrn()).page(query.limit(), query.after()).build())
                .map(answer -> page(answer.body(), Recommendation.class));
    }

    @Override
    public Mono<RecommendationsSummary> getRecommendationsSummary(AccountScope scope, DriftCaller caller) {
        return get(scope, caller, b -> new Query(b, "/v1/recommendations/summary").account(scope).build())
                .map(answer -> read(answer.body(), RecommendationsSummary.class));
    }

    @Override
    public Mono<Recommendation> getRecommendation(AccountScope scope, DriftCaller caller, String recommendationId) {
        return get(scope, caller, b -> b.path("/v1/recommendations/{id}").build(Map.of("id", recommendationId)))
                .map(answer -> read(answer.body(), Recommendation.class));
    }

    @Override
    public Mono<DriftResults.ApplyStarted> applyRecommendation(AccountScope scope, DriftCaller caller, String recommendationId,
            DriftCommands.Decision decision, DriftCommands.Meta meta) {
        return send(HttpMethod.POST, scope, caller, b -> b.path("/v1/recommendations/{id}:apply").build(Map.of("id", recommendationId)),
                decision(decision), meta)
                .map(answer -> read(answer.body(), DriftResults.ApplyStarted.class));
    }

    @Override
    public Mono<Recommendation> dismissRecommendation(AccountScope scope, DriftCaller caller, String recommendationId, DriftCommands.Decision decision,
            DriftCommands.Meta meta) {
        return send(HttpMethod.POST, scope, caller, b -> b.path("/v1/recommendations/{id}:dismiss").build(Map.of("id", recommendationId)),
                decision(decision), meta)
                .map(answer -> read(answer.body(), Recommendation.class));
    }

    // ---- plumbing

    /** A status and a body, for the one write whose status means something (an alarm's PUT: 201 created, 200 replaced). */
    private record Answer(int status, JsonNode body) {
    }

    /**
     * Query parameters whose values are URI variables, so each is encoded strictly ({@code +},
     * {@code &}, {@code :} and braces included) and a null or blank value leaves the parameter out.
     */
    private static final class Query {
        private final UriBuilder builder;
        private final Map<String, Object> vars = new HashMap<>();

        Query(UriBuilder builder, String path) {
            this.builder = builder.path(path);
        }

        Query account(AccountScope scope) {
            return param("accountId", scope.accountId());
        }

        Query region(AccountScope scope) {
            return param("region", scope.region());
        }

        Query param(String name, Object value) {
            String text = value == null ? null : value.toString();
            if (text != null && !text.isBlank()) {
                builder.queryParam(name, "{" + name + "}");
                vars.put(name, text);
            }
            return this;
        }

        Query page(int limit, String after) {
            return param("limit", limit).param("after", after);
        }

        URI build() {
            return builder.build(vars);
        }
    }

    /** A decision's body: the reason and the actor, and {@code until} for an ignore or a dismissal; what is not set is left out. */
    private static Map<String, Object> decision(DriftCommands.Decision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (decision != null) {
            putIfSet(body, "reason", decision.reason());
            putIfSet(body, "actor", decision.actor());
            putIfSet(body, "until", decision.until() == null ? null : decision.until().toString());
        }
        return body;
    }

    private static void putIfSet(Map<String, Object> body, String name, Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            body.put(name, value);
        }
    }

    private Mono<Answer> get(AccountScope scope, DriftCaller caller, Function<UriBuilder, URI> uri) {
        return send(HttpMethod.GET, scope, caller, uri, null, null);
    }

    private Mono<Answer> send(HttpMethod method, AccountScope scope, DriftCaller caller, Function<UriBuilder, URI> uri, Object body,
            DriftCommands.Meta meta) {
        WebClient.RequestBodySpec request = client().method(method).uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearer(scope, caller))
                .accept(MediaType.APPLICATION_JSON);
        if (meta != null) {
            if (meta.idempotencyKey() != null && !meta.idempotencyKey().isBlank()) {
                request = request.header(IDEMPOTENCY_KEY, meta.idempotencyKey());
            }
            if (meta.requestId() != null && !meta.requestId().isBlank()) {
                request = request.header(REQUEST_ID, meta.requestId());
            }
            if (meta.onBehalfOf() != null && !meta.onBehalfOf().isBlank()) {
                request = request.header(ON_BEHALF_OF, meta.onBehalfOf());
            }
        }
        WebClient.RequestHeadersSpec<?> ready = body == null ? request : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        return ready.exchangeToMono(r -> {
            int status = r.statusCode().value();
            if (r.statusCode().is2xxSuccessful()) {
                return r.bodyToMono(JsonNode.class).defaultIfEmpty(JsonNodeFactory.instance.nullNode()).map(node -> new Answer(status, node));
            }
            return r.bodyToMono(String.class).defaultIfEmpty("").flatMap(text -> Mono.<Answer>error(failure(r, text)));
        }).timeout(properties.timeout());
    }

    private <T> Page<T> page(JsonNode node, Class<T> type) {
        List<T> items = new ArrayList<>();
        for (JsonNode item : node.path("items")) {
            items.add(read(item, type));
        }
        return new Page<>(items, node.hasNonNull("next") ? node.get("next").asText() : null);
    }

    private <T> T read(JsonNode node, Class<T> type) {
        try {
            T value = json.treeToValue(node, type);
            if (value == null) {
                throw new IllegalStateException("the drift service answered no body");
            }
            return value;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("the drift service answered what the console cannot read: " + e.getMessage(), e);
        }
    }

    private RuntimeException failure(ClientResponse response, String body) {
        JsonNode node = parse(body);
        int status = response.statusCode().value();
        String error = node.hasNonNull("error") ? node.get("error").asText() : "drift service answered " + status;
        switch (status) {
            case 400:
                List<Problem> problems = new ArrayList<>();
                for (JsonNode p : node.path("problems")) {
                    problems.add(Problem.error(p.path("code").asText("invalid"), p.hasNonNull("field") ? p.get("field").asText() : null,
                            p.path("message").asText(error)));
                }
                return new InvalidInputException(error, problems);
            case 404:
                return new NotFoundException(error);
            case 409:
                return DriftConflictException.of(error, detail(node));
            case 429:
                return new TooManyRequestsException(error, retryAfter(response, node));
            case 502:
                return new DriftGatewayException(error, node.hasNonNull("correlationId") ? node.get("correlationId").asText() : null,
                        node.hasNonNull("detail") ? node.get("detail") : null);
            default:
                return WebClientResponseException.create(status, error, response.headers().asHttpHeaders(), body.getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8);
        }
    }

    /** A 409's detail as a map for the coded conflict, without the nulls a copied map refuses. */
    private Map<String, Object> detail(JsonNode node) {
        JsonNode detail = node.path("detail");
        if (!detail.isObject()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        detail.properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                out.put(e.getKey(), json.convertValue(e.getValue(), Object.class));
            }
        });
        return out;
    }

    private static int retryAfter(ClientResponse response, JsonNode node) {
        String header = response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        try {
            if (header != null && !header.isBlank()) {
                return Integer.parseInt(header.strip());
            }
        } catch (NumberFormatException e) {
            // an HTTP date or garbage: fall back to the body's figure
        }
        return node.path("retryAfter").asInt(1);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            JsonNode node = json.readTree(body);
            return node == null || !node.isObject() ? JsonNodeFactory.instance.objectNode() : node;
        } catch (Exception e) {
            return JsonNodeFactory.instance.objectNode().put("error", body.length() > 200 ? body.substring(0, 200) : body);
        }
    }

    /** The person's internal JWT in their account, or the BFF's own token (no account) for the console-internal principal. */
    private String bearer(AccountScope scope, DriftCaller caller) {
        InternalToken token = caller == null || caller.isInternal()
                ? InternalToken.service(properties.serviceName(), trust.issuer())
                : InternalToken.user(properties.serviceName(), caller.userId(), scope.accountId(), scope.region(), caller.groups(), caller.roles());
        return "Bearer " + trust.mint(token);
    }

    private WebClient client() {
        WebClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = builder.clone().baseUrl(baseUrl.get()).build();
                    client = c;
                }
            }
        }
        return c;
    }
}

package com.iaf.console.bff.resources.adapters.outbound.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.iaf.console.bff.resources.application.ResourcesConfiguration;
import com.iaf.console.bff.resources.application.StateProperties;
import com.iaf.console.bff.resources.domain.GoneException;
import com.iaf.console.bff.resources.domain.StateAnswer;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.resources.domain.StateCommands;
import com.iaf.console.bff.resources.domain.StateQuery;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.bff.resources.domain.Tombstone;
import com.iaf.console.bff.resources.ports.outbound.StatePort;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.domain.InternalToken;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The state service over HTTP, as its contract says: an internal JWT for the user in their account
 * on every call, {@code Idempotency-Key} and {@code X-Console-Request-Id} on commands, the ETag of a
 * resource read back and forth, and its error bodies turned into the kit's exceptions. With no
 * URL configured it points at the in-process demo stand-in.
 */
@Component
public class HttpStatePort implements StatePort {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String CONSOLE_REQUEST_ID = "X-Console-Request-Id";

    private final WebClient.Builder builder;
    private final Supplier<String> baseUrl;
    private final Trust trust;
    private final StateProperties properties;
    private final ObjectMapper json;
    private volatile WebClient client;

    @Autowired
    public HttpStatePort(WebClient.Builder builder, Trust trust, StateProperties properties, ObjectMapper json, Environment environment) {
        this(builder, () -> {
            if (properties.url() != null && !properties.url().isBlank()) {
                return properties.url().replaceAll("/+$", "");
            }
            String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "18081"));
            return "http://127.0.0.1:" + port + ResourcesConfiguration.DEMO_PATH;
        }, trust, properties, json);
    }

    HttpStatePort(WebClient.Builder builder, Supplier<String> baseUrl, Trust trust, StateProperties properties, ObjectMapper json) {
        this.builder = builder;
        this.baseUrl = baseUrl;
        this.trust = trust;
        this.properties = properties;
        this.json = json;
    }

    /** The state service's URL, resolved on first use (the demo stand-in needs the server's port). */
    public String url() {
        return baseUrl.get();
    }

    @Override
    public Mono<Page<StateResource>> list(AccountScope scope, StateCaller caller, StateQuery query) {
        Function<UriBuilder, URI> uri = b -> {
            b.path("/v1/resources").queryParam("accountId", scope.accountId()).queryParam("region", scope.region());
            put(b, "type", query.type());
            put(b, "state", query.state());
            put(b, "name", query.name());
            put(b, "wfrn", query.wfrn());
            put(b, "sort", query.sort());
            if (query.drift() != null) {
                b.queryParam("drift", query.drift());
            }
            if (query.limit() != null) {
                b.queryParam("limit", query.limit());
            }
            put(b, "after", query.after());
            query.tags().forEach((name, value) -> b.queryParam("tag." + name, value));
            return b.build();
        };
        return exchange(client().get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(scope, caller)).accept(MediaType.APPLICATION_JSON),
                r -> r.bodyToMono(JsonNode.class).map(this::pageOfResources));
    }

    @Override
    public Mono<StateAnswer> get(AccountScope scope, StateCaller caller, String wfrn, String ifNoneMatch) {
        WebClient.RequestHeadersSpec<?> spec = client().get().uri("/v1/wfrn/{wfrn}", wfrn)
                .header(HttpHeaders.AUTHORIZATION, bearer(scope, caller)).accept(MediaType.APPLICATION_JSON);
        if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
            spec = spec.header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        return spec.exchangeToMono(r -> {
            if (r.statusCode().value() == 304) {
                return r.releaseBody().thenReturn(StateAnswer.unchanged(r.headers().asHttpHeaders().getETag()));
            }
            if (r.statusCode().is2xxSuccessful()) {
                String etag = r.headers().asHttpHeaders().getETag();
                return r.bodyToMono(StateResource.class).map(resource -> new StateAnswer(resource, etag, false));
            }
            return r.bodyToMono(String.class).defaultIfEmpty("").flatMap(body -> Mono.error(failure(r, body)));
        }).timeout(properties.timeout());
    }

    @Override
    public Mono<Page<JsonNode>> events(AccountScope scope, StateCaller caller, String wfrn, Integer limit, String after) {
        Function<UriBuilder, URI> uri = b -> {
            b.path("/v1/wfrn/{wfrn}/events");
            if (limit != null) {
                b.queryParam("limit", limit);
            }
            put(b, "after", after);
            return b.build(wfrn);
        };
        return exchange(client().get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(scope, caller)).accept(MediaType.APPLICATION_JSON),
                r -> r.bodyToMono(JsonNode.class).map(node -> {
                    List<JsonNode> items = new ArrayList<>();
                    node.path("items").forEach(items::add);
                    return new Page<>(items, node.hasNonNull("next") ? node.get("next").asText() : null);
                }));
    }

    @Override
    public Mono<StateResource> mint(AccountScope scope, StateCaller caller, StateCommands.Mint command, StateCommands.Meta meta) {
        return command(scope, caller, b -> b.path("/v1/resources:mint").build(), command, meta);
    }

    @Override
    public Mono<StateResource> requestUpdate(AccountScope scope, StateCaller caller, String wfrn, StateCommands.Update command,
            StateCommands.Meta meta) {
        return command(scope, caller, b -> b.path("/v1/wfrn/{wfrn}:request-update").build(wfrn), command, meta);
    }

    @Override
    public Mono<StateResource> requestDelete(AccountScope scope, StateCaller caller, String wfrn, StateCommands.Delete command,
            StateCommands.Meta meta) {
        return command(scope, caller, b -> b.path("/v1/wfrn/{wfrn}:request-delete").build(wfrn), command, meta);
    }

    @Override
    public Mono<StateResource> tag(AccountScope scope, StateCaller caller, String wfrn, StateCommands.Tag command, StateCommands.Meta meta) {
        return command(scope, caller, b -> b.path("/v1/wfrn/{wfrn}:tag").build(wfrn), command, meta);
    }

    // ---- plumbing

    private Mono<StateResource> command(AccountScope scope, StateCaller caller, Function<UriBuilder, URI> uri, Object body,
            StateCommands.Meta meta) {
        WebClient.RequestBodySpec spec = client().method(HttpMethod.POST).uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(scope, caller))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON);
        if (meta != null && meta.idempotencyKey() != null && !meta.idempotencyKey().isBlank()) {
            spec = spec.header(IDEMPOTENCY_KEY, meta.idempotencyKey());
        }
        if (meta != null && meta.requestId() != null) {
            spec = spec.header(CONSOLE_REQUEST_ID, meta.requestId());
        }
        return exchange(spec.bodyValue(body), r -> r.bodyToMono(StateResource.class));
    }

    private <T> Mono<T> exchange(WebClient.RequestHeadersSpec<?> spec, Function<ClientResponse, Mono<T>> ok) {
        return spec.exchangeToMono(r -> r.statusCode().is2xxSuccessful() ? ok.apply(r)
                : r.bodyToMono(String.class).defaultIfEmpty("").flatMap(body -> Mono.<T>error(failure(r, body))))
                .timeout(properties.timeout());
    }

    /** The state service's error, as the kit's exception the handler maps the way the contract says. */
    private RuntimeException failure(ClientResponse response, String body) {
        JsonNode node = parse(body);
        String error = node.hasNonNull("error") ? node.get("error").asText() : "state service answered " + response.statusCode().value();
        switch (response.statusCode().value()) {
            case 400:
                return new InvalidInputException(error, problems(node));
            case 404:
                return new NotFoundException(error);
            case 409:
                return new ConflictException(error, node.isObject() ? node : null);
            case 410:
                Tombstone tombstone;
                try {
                    tombstone = json.treeToValue(node, Tombstone.class);
                } catch (Exception e) {
                    tombstone = new Tombstone(error, null, null, null, null);
                }
                return new GoneException(tombstone);
            default:
                return WebClientResponseException.create(response.statusCode().value(), error, response.headers().asHttpHeaders(),
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
    }

    private static List<Problem> problems(JsonNode node) {
        List<Problem> problems = new ArrayList<>();
        for (JsonNode p : node.path("problems")) {
            String level = p.path("level").asText("error");
            problems.add(new Problem("warning".equals(level) ? Problem.Level.WARNING : Problem.Level.ERROR, text(p, "code"), text(p, "step"),
                    text(p, "field"), text(p, "message"), null));
        }
        return problems;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            JsonNode node = json.readTree(body);
            return node == null ? JsonNodeFactory.instance.objectNode() : node;
        } catch (Exception e) {
            return JsonNodeFactory.instance.objectNode().put("error", body.length() > 200 ? body.substring(0, 200) : body);
        }
    }

    private Page<StateResource> pageOfResources(JsonNode node) {
        List<StateResource> items = new ArrayList<>();
        for (JsonNode item : node.path("items")) {
            try {
                items.add(json.treeToValue(item, StateResource.class));
            } catch (Exception e) {
                throw new IllegalStateException("the state service answered a resource the console cannot read: " + e.getMessage(), e);
            }
        }
        return new Page<>(items, node.hasNonNull("next") ? node.get("next").asText() : null);
    }

    private static void put(UriBuilder b, String name, String value) {
        if (value != null && !value.isBlank()) {
            b.queryParam(name, value);
        }
    }

    private String bearer(AccountScope scope, StateCaller caller) {
        return "Bearer " + trust.mint(InternalToken.user(properties.serviceName(), caller.userId(), scope.accountId(), scope.region(), caller.groups(),
                caller.roles(), caller.rolesUntil()));
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

    /** For the configuration's diagnostics. */
    public Map<String, Object> describe() {
        return Map.of("url", url(), "audience", properties.serviceName(), "timeout", properties.timeout().toString());
    }
}

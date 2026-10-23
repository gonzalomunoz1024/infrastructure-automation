package com.iaf.console.bff.search.adapters.outbound.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.search.application.SearchConfiguration;
import com.iaf.console.bff.search.application.SearchProperties;
import com.iaf.console.bff.search.domain.CountsQuery;
import com.iaf.console.bff.search.domain.IndexStatus;
import com.iaf.console.bff.search.domain.ResourceCounts;
import com.iaf.console.bff.search.domain.SearchHit;
import com.iaf.console.bff.search.domain.SearchPage;
import com.iaf.console.bff.search.domain.SearchQuery;
import com.iaf.console.bff.search.domain.Suggestion;
import com.iaf.console.bff.search.ports.outbound.SearchPort;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.domain.InternalToken;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.errors.TooManyRequestsException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The search indexer over HTTP, as its contract says: an internal JWT for the person in their
 * account on every call (the console-internal principal's, without an account, for counts over
 * several accounts), free text sent as a strictly encoded parameter, and its errors turned into
 * the kit's exceptions (429 keeps its {@code Retry-After}). With no URL configured it points at the
 * in-process demo stand-in.
 */
@Component
public class HttpSearchPort implements SearchPort {

    private static final Logger log = LoggerFactory.getLogger(HttpSearchPort.class);

    private final WebClient.Builder builder;
    private final Supplier<String> baseUrl;
    private final Trust trust;
    private final SearchProperties properties;
    private final ObjectMapper json;
    private volatile WebClient client;

    @Autowired
    public HttpSearchPort(WebClient.Builder builder, Trust trust, SearchProperties properties, ObjectMapper json, Environment environment) {
        this(builder, () -> {
            if (properties.url() != null && !properties.url().isBlank()) {
                return properties.url().replaceAll("/+$", "");
            }
            String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "18081"));
            return "http://127.0.0.1:" + port + SearchConfiguration.DEMO_PATH;
        }, trust, properties, json);
    }

    HttpSearchPort(WebClient.Builder builder, Supplier<String> baseUrl, Trust trust, SearchProperties properties, ObjectMapper json) {
        this.builder = builder;
        this.baseUrl = baseUrl;
        this.trust = trust;
        this.properties = properties;
        this.json = json;
    }

    /** The indexer's URL, resolved on first use (the demo stand-in needs the server's port). */
    public String url() {
        return baseUrl.get();
    }

    @Override
    public Mono<SearchPage> search(AccountScope scope, StateCaller caller, SearchQuery query) {
        return get(scope, caller, b -> filters(b, "/v1/search", scope, query, true), node -> {
            List<SearchHit> items = new ArrayList<>();
            for (JsonNode item : node.path("items")) {
                items.add(read(item, SearchHit.class));
            }
            return new SearchPage(items, node.hasNonNull("next") ? node.get("next").asText() : null,
                    node.hasNonNull("didYouMean") ? node.get("didYouMean").asText() : null);
        });
    }

    @Override
    public Mono<List<Suggestion>> suggest(AccountScope scope, StateCaller caller, String q, String type, int limit) {
        return get(scope, caller, b -> {
            Map<String, Object> vars = new HashMap<>();
            b.path("/v1/suggest").queryParam("region", scope.region());
            param(b, vars, "q", q);
            param(b, vars, "type", type);
            b.queryParam("limit", limit);
            return b.build(vars);
        }, node -> {
            List<Suggestion> items = new ArrayList<>();
            for (JsonNode item : node.path("items")) {
                items.add(read(item, Suggestion.class));
            }
            return items;
        });
    }

    @Override
    public Mono<ResourceCounts> counts(AccountScope scope, StateCaller caller, SearchQuery query) {
        return get(scope, caller, b -> filters(b, "/v1/counts", scope, query, false), node -> read(node, ResourceCounts.class));
    }

    @Override
    public Mono<JsonNode> countAcross(AccountScope scope, List<String> accountIds, CountsQuery query) {
        if (accountIds.isEmpty() || accountIds.size() > MAX_ACCOUNTS) {
            return Mono.error(new IllegalArgumentException("a counts call names 1 to " + MAX_ACCOUNTS + " accounts, not " + accountIds.size()));
        }
        log.debug("counts of {} accounts as the console-internal principal, for {} in {}", accountIds.size(), scope.principal(), scope.accountId());
        return get(serviceBearer(), b -> counts(b, accountIds, query), node -> node);
    }

    @Override
    public Mono<IndexStatus> getIndexStatus(AccountScope scope, StateCaller caller) {
        return get(scope, caller, b -> b.path("/v1/index/status").build(), node -> read(node, IndexStatus.class));
    }

    // ---- plumbing

    /** The search filters as query parameters; {@code drift} is a parameter on counts and a {@code drift:} term on a search. */
    private static URI filters(UriBuilder b, String path, AccountScope scope, SearchQuery query, boolean search) {
        Map<String, Object> vars = new HashMap<>();
        b.path(path).queryParam("region", scope.region());
        String q = query.q() == null ? "" : query.q().strip();
        if (search && query.drift() != null) {
            q = (q + " drift:" + query.drift()).strip();
        }
        if (search || !q.isEmpty()) {
            param(b, vars, "q", q);
        }
        param(b, vars, "type", query.type());
        param(b, vars, "state", query.state());
        int i = 0;
        for (Map.Entry<String, String> tag : query.tags().entrySet()) {
            String var = "tag" + i++;
            b.queryParam("tag." + tag.getKey(), "{" + var + "}");
            vars.put(var, tag.getValue());
        }
        if (!search && query.drift() != null) {
            b.queryParam("drift", query.drift());
        }
        if (search) {
            param(b, vars, "sort", query.sort());
            if (query.limit() != null) {
                b.queryParam("limit", query.limit());
            }
            param(b, vars, "after", query.after());
        }
        return b.build(vars);
    }

    /** {@code /v1/counts} with every filter of a counts query, and {@code accountId} once per account when there are any. */
    private static URI counts(UriBuilder b, List<String> accountIds, CountsQuery query) {
        Map<String, Object> vars = new HashMap<>();
        b.path("/v1/counts");
        for (int i = 0; i < accountIds.size(); i++) {
            b.queryParam("accountId", "{account" + i + "}");
            vars.put("account" + i, accountIds.get(i));
        }
        param(b, vars, "q", query.q());
        param(b, vars, "type", query.type());
        param(b, vars, "region", query.region());
        param(b, vars, "state", query.state());
        int i = 0;
        for (Map.Entry<String, String> tag : query.tags().entrySet()) {
            String var = "tag" + i++;
            b.queryParam("tag." + tag.getKey(), "{" + var + "}");
            vars.put(var, tag.getValue());
        }
        if (query.drift() != null) {
            b.queryParam("drift", query.drift());
        }
        param(b, vars, "groupBy", query.groupBy());
        return b.build(vars);
    }

    /** A query parameter whose value is a URI variable, so it is encoded strictly ({@code +}, {@code &}, braces and all). */
    private static void param(UriBuilder b, Map<String, Object> vars, String name, String value) {
        if (value != null && !value.isBlank()) {
            b.queryParam(name, "{" + name + "}");
            vars.put(name, value);
        }
    }

    private <T> Mono<T> get(AccountScope scope, StateCaller caller, Function<UriBuilder, URI> uri, Function<JsonNode, T> body) {
        return get(bearer(scope, caller), uri, body);
    }

    private <T> Mono<T> get(String bearer, Function<UriBuilder, URI> uri, Function<JsonNode, T> body) {
        return client().get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer).accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(r -> r.statusCode().is2xxSuccessful() ? r.bodyToMono(JsonNode.class).map(body)
                        : r.bodyToMono(String.class).defaultIfEmpty("").flatMap(text -> Mono.<T>error(failure(r, text))))
                .timeout(properties.timeout());
    }

    private <T> T read(JsonNode node, Class<T> type) {
        try {
            return json.treeToValue(node, type);
        } catch (Exception e) {
            throw new IllegalStateException("the search indexer answered what the console cannot read: " + e.getMessage(), e);
        }
    }

    private RuntimeException failure(ClientResponse response, String body) {
        JsonNode node = parse(body);
        String error = node.hasNonNull("error") ? node.get("error").asText() : "search indexer answered " + response.statusCode().value();
        switch (response.statusCode().value()) {
            case 400:
                List<Problem> problems = new ArrayList<>();
                for (JsonNode p : node.path("problems")) {
                    problems.add(Problem.error(p.path("code").asText("invalid"), p.hasNonNull("field") ? p.get("field").asText() : "/q",
                            p.path("message").asText(error)));
                }
                return new InvalidInputException(error, problems);
            case 404:
                return new NotFoundException(error);
            case 429:
                String retry = response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                int seconds;
                try {
                    seconds = retry == null ? 1 : Integer.parseInt(retry.strip());
                } catch (NumberFormatException e) {
                    seconds = 1;
                }
                return new TooManyRequestsException(error, seconds);
            default:
                return WebClientResponseException.create(response.statusCode().value(), error, response.headers().asHttpHeaders(),
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
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

    private String bearer(AccountScope scope, StateCaller caller) {
        return "Bearer " + trust.mint(InternalToken.user(properties.serviceName(), caller.userId(), scope.accountId(), scope.region(), caller.groups(),
                caller.roles()));
    }

    /** The console-internal principal's token: the BFF itself, no account. */
    private String serviceBearer() {
        return "Bearer " + trust.mint(InternalToken.service(properties.serviceName(), trust.issuer()));
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

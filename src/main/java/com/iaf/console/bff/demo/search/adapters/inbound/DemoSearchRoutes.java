package com.iaf.console.bff.demo.search.adapters.inbound;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.iaf.console.bff.demo.identity.application.DemoCaller;
import com.iaf.console.bff.demo.identity.application.DemoTokens;
import com.iaf.console.bff.demo.search.application.DemoSearchIndex;
import com.iaf.console.bff.search.application.SearchConfiguration;
import com.iaf.console.bff.search.application.SearchProperties;
import com.iaf.console.bff.search.domain.SearchHit;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * The search indexer's contract, served in process for the demo under {@code /demo-search}:
 * {@code /v1/search}, {@code /v1/suggest} and {@code /v1/counts}. The caller is the BFF's
 * internal JWT, read as the real indexer reads it: a user searches the token's account, the
 * console itself names one with {@code accountId}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoSearchRoutes {

    private static final String PREFIX = SearchConfiguration.DEMO_PATH;

    private final DemoSearchIndex index;
    private final DemoTokens tokens;
    private final String audience;

    public DemoSearchRoutes(DemoSearchIndex index, DemoTokens tokens, SearchProperties search) {
        this.index = index;
        this.tokens = tokens;
        this.audience = search.serviceName();
    }

    @Bean
    public RouterFunction<ServerResponse> demoSearchRouter() {
        return route(GET(PREFIX + "/v1/search"), r -> answer(r, account -> {
            Page<SearchHit> page = index.search(account, param(r, "q"), filters(r), param(r, "sort"), integer(r, "limit"), param(r, "after"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", page.items());
            body.put("next", page.next());
            return body;
        }))
                .andRoute(GET(PREFIX + "/v1/suggest"), r -> answer(r, account -> {
                    Integer limit = integer(r, "limit");
                    return Map.of("items", index.suggest(account, param(r, "region"), param(r, "q"), param(r, "type"), limit == null ? 8 : limit));
                }))
                .andRoute(GET(PREFIX + "/v1/counts"), r -> answer(r, account -> index.counts(account, param(r, "q"), filters(r))));
    }

    private Mono<ServerResponse> answer(ServerRequest request, Function<String, Object> work) {
        Optional<DemoCaller> caller = tokens.verify(request.headers().firstHeader(HttpHeaders.AUTHORIZATION), audience);
        if (caller.isEmpty()) {
            return json(HttpStatus.UNAUTHORIZED, Map.of("error", "invalid or missing token"));
        }
        DemoCaller c = caller.get();
        String account = c.internal() ? param(request, "accountId") : c.accountId();
        if (account == null) {
            return json(HttpStatus.BAD_REQUEST, Map.of("error", "accountId is required for the console-internal principal"));
        }
        if (!c.internal() && param(request, "accountId") != null && !param(request, "accountId").equals(account)) {
            return json(HttpStatus.NOT_FOUND, Map.of("error", "account " + param(request, "accountId") + " not found"));
        }
        try {
            return json(HttpStatus.OK, work.apply(account));
        } catch (InvalidInputException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            body.put("problems", e.problems());
            return json(HttpStatus.BAD_REQUEST, body);
        }
    }

    private static DemoSearchIndex.Filters filters(ServerRequest request) {
        Map<String, String> tags = new LinkedHashMap<>();
        request.queryParams().forEach((name, values) -> {
            if (name.startsWith("tag.") && name.length() > 4 && !values.isEmpty()) {
                tags.put(name.substring(4), values.get(0));
            }
        });
        String drift = param(request, "drift");
        if (drift != null && !drift.equals("true") && !drift.equals("false")) {
            throw new InvalidInputException("drift is true or false", List.of(Problem.error("enum", "/drift", "drift is true or false")));
        }
        return new DemoSearchIndex.Filters(param(request, "region"), param(request, "type"), param(request, "state"), tags,
                drift == null ? null : Boolean.valueOf(drift));
    }

    private static String param(ServerRequest request, String name) {
        return request.queryParam(name).filter(v -> !v.isBlank()).orElse(null);
    }

    private static Integer integer(ServerRequest request, String name) {
        String value = param(request, name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            throw new InvalidInputException(name + " is a number", List.of(Problem.error("type", "/" + name, name + " is a number")));
        }
    }

    private static Mono<ServerResponse> json(HttpStatus status, Object body) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}

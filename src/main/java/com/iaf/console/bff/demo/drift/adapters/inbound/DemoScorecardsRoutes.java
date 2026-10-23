package com.iaf.console.bff.demo.drift.adapters.inbound;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.Arrays;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.console.bff.demo.drift.application.DemoScorecards;
import com.iaf.console.bff.demo.identity.application.DemoCaller;
import com.iaf.console.bff.demo.identity.application.DemoTokens;
import com.iaf.console.bff.drift.application.DriftProperties;
import com.iaf.console.bff.scorecards.application.ScorecardsConfiguration;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * The drift service's scorecards contract, served in process for the demo under
 * {@code /demo-drift}: packs, an account's scorecard, the heat map (console-internal callers only;
 * a person hears 404), a rule's failing resources, a resource's results and the one-click fix.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoScorecardsRoutes {

    private static final String PREFIX = ScorecardsConfiguration.DEMO_PATH + "/v1/scorecards";

    private final DemoScorecards scorecards;
    private final DemoTokens tokens;
    private final ObjectMapper json;
    private final DriftProperties properties;

    public DemoScorecardsRoutes(DemoScorecards scorecards, DemoTokens tokens, ObjectMapper json, DriftProperties properties) {
        this.properties = properties;
        this.scorecards = scorecards;
        this.tokens = tokens;
        this.json = json;
    }

    @Bean
    public RouterFunction<ServerResponse> demoScorecardsRouter() {
        return route(GET(PREFIX + "/packs"), r -> answer(r, c -> ok(scorecards.packs())))
                .andRoute(GET(PREFIX + "/account"), r -> answer(r, c -> ok(scorecards.scorecard(account(r, c)))))
                .andRoute(GET(PREFIX + "/heatmap"), r -> answer(r, c -> {
                    if (!c.internal()) {
                        throw new NotFoundException("not found");
                    }
                    String ids = r.queryParam("accountIds").orElse("");
                    return ok(scorecards.heatmap(Arrays.stream(ids.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList()));
                }))
                .andRoute(GET(PREFIX + "/packs/{pack}/rules/{ruleId}/failing"), r -> answer(r, c -> ok(scorecards.failing(account(r, c),
                        r.pathVariable("pack"), r.pathVariable("ruleId"), r.queryParam("type").filter(t -> !t.isBlank()).orElse(null),
                        r.queryParam("limit").map(Integer::parseInt).orElse(50)))))
                .andRoute(GET(PREFIX + "/resources"), r -> answer(r, c -> ok(scorecards.resource(account(r, c), r.queryParam("wfrn").orElse("")))))
                .andRoute(POST(PREFIX + "/packs/{pack}/rules/{ruleId}:remediate"), r -> r.bodyToMono(String.class).defaultIfEmpty("{}")
                        .flatMap(text -> answer(r, c -> {
                            JsonNode body;
                            try {
                                body = json.readTree(text);
                            } catch (java.io.IOException e) {
                                body = JsonNodeFactory.instance.objectNode();
                            }
                            Map<String, Object> done = scorecards.remediate(account(r, c), r.pathVariable("pack"), r.pathVariable("ruleId"), body,
                                    r.headers().firstHeader("X-On-Behalf-Of"), c.subject(), r.headers().firstHeader("Idempotency-Key"),
                                    r.headers().firstHeader("X-Console-Request-Id"));
                            return ok(done);
                        })));
    }

    private static String account(ServerRequest request, DemoCaller caller) {
        String named = request.queryParam("accountId").filter(a -> !a.isBlank()).orElse(null);
        if (caller.internal()) {
            if (named == null) {
                throw new InvalidInputException("accountId is required for a call that acts for no account", List.of());
            }
            return named;
        }
        if (named != null && !named.equals(caller.accountId())) {
            throw NotFoundException.of("account", named);
        }
        return caller.accountId();
    }

    private Mono<ServerResponse> answer(ServerRequest request, Function<DemoCaller, Mono<ServerResponse>> work) {
        Optional<DemoCaller> caller = tokens.verify(request.headers().firstHeader(HttpHeaders.AUTHORIZATION), properties.serviceName());
        if (caller.isEmpty()) {
            return json(HttpStatus.UNAUTHORIZED, Map.of("error", "invalid or missing token"));
        }
        try {
            return work.apply(caller.get());
        } catch (InvalidInputException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            body.put("problems", e.problems());
            return json(HttpStatus.BAD_REQUEST, body);
        } catch (NotFoundException e) {
            return json(HttpStatus.NOT_FOUND, Map.of("error", e.getMessage()));
        } catch (ConflictException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            if (e.detail() != null) {
                body.put("detail", e.detail());
            }
            return json(HttpStatus.CONFLICT, body);
        } catch (NumberFormatException e) {
            return json(HttpStatus.BAD_REQUEST, Map.of("error", "limit is a number"));
        }
    }

    private static Mono<ServerResponse> ok(Object body) {
        return json(HttpStatus.OK, body);
    }

    private static Mono<ServerResponse> json(HttpStatus status, Object body) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}

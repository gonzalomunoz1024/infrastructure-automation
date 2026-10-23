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
import com.iaf.console.bff.demo.search.application.DemoVocabulary;
import com.iaf.console.bff.search.application.SearchConfiguration;
import com.iaf.console.bff.search.domain.SearchPage;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The search indexer's contract, served in process for the demo under {@code /demo-search}:
 * {@code /v1/search}, {@code /v1/search:interpret}, {@code /v1/suggest}, {@code /v1/counts} and
 * {@code /v1/index/status}. The
 * caller is the BFF's internal JWT, read as the real indexer reads it: a user searches the
 * token's account (another account named is a 404), the console itself names one with
 * {@code accountId}, or, for the counts, up to 200 with {@code accountId} repeated, and sees the
 * whole index's status when it names none.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoSearchRoutes {

    private static final String PREFIX = SearchConfiguration.DEMO_PATH;
    private static final int MAX_ACCOUNTS = 200;

    private final DemoSearchIndex index;
    private final DemoVocabulary vocabulary;
    private final DemoTokens tokens;

    public DemoSearchRoutes(DemoSearchIndex index, DemoVocabulary vocabulary, DemoTokens tokens) {
        this.index = index;
        this.vocabulary = vocabulary;
        this.tokens = tokens;
    }

    @Bean
    public RouterFunction<ServerResponse> demoSearchRouter() {
        return route(GET(PREFIX + "/v1/search"), r -> answer(r, account -> {
            SearchPage page = index.search(account, param(r, "q"), filters(r), param(r, "sort"), integer(r, "limit"), param(r, "after"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", page.items());
            body.put("next", page.next());
            if (page.didYouMean() != null) {
                body.put("didYouMean", page.didYouMean());
            }
            return body;
        }))
                .andRoute(GET(PREFIX + "/v1/search:interpret"), r -> answerLater(r, account -> vocabulary.of(account)
                        .map(known -> index.interpret(account, param(r, "q"), param(r, "type"), known))))
                .andRoute(GET(PREFIX + "/v1/suggest"), r -> answer(r, account -> {
                    Integer limit = integer(r, "limit");
                    return Map.of("items", index.suggest(account, param(r, "region"), param(r, "q"), param(r, "type"), limit == null ? 8 : limit));
                }))
                .andRoute(GET(PREFIX + "/v1/counts"), r -> answerAccounts(r, accounts -> index.counts(accounts, param(r, "q"), filters(r), param(r, "groupBy"))))
                .andRoute(GET(PREFIX + "/v1/index/status"), this::status);
    }

    /** The index's status: a user's own account's documents (another account named is a 404), the internal principal's account or the whole index. */
    private Mono<ServerResponse> status(ServerRequest request) {
        Optional<DemoCaller> caller = tokens.verify(request.headers().firstHeader(HttpHeaders.AUTHORIZATION));
        if (caller.isEmpty()) {
            return json(HttpStatus.UNAUTHORIZED, Map.of("error", "invalid or missing token"));
        }
        DemoCaller c = caller.get();
        String named = param(request, "accountId");
        if (!c.internal() && named != null && !named.equals(c.accountId())) {
            return json(HttpStatus.NOT_FOUND, Map.of("error", "account " + named + " not found"));
        }
        return json(HttpStatus.OK, index.status(c.internal() ? named : c.accountId()));
    }

    /** A call over accounts: the token's own for a user (another one named is a 404), the 1 to 200 named for the console-internal principal. */
    private Mono<ServerResponse> answerAccounts(ServerRequest request, Function<List<String>, Object> work) {
        Optional<DemoCaller> caller = tokens.verify(request.headers().firstHeader(HttpHeaders.AUTHORIZATION));
        if (caller.isEmpty()) {
            return json(HttpStatus.UNAUTHORIZED, Map.of("error", "invalid or missing token"));
        }
        DemoCaller c = caller.get();
        List<String> named = request.queryParams().getOrDefault("accountId", List.of()).stream().map(String::strip).filter(v -> !v.isEmpty())
                .distinct().toList();
        List<String> accounts;
        if (c.internal()) {
            if (named.isEmpty() || named.size() > MAX_ACCOUNTS) {
                return json(HttpStatus.BAD_REQUEST, Map.of("error", "the console-internal principal names 1 to " + MAX_ACCOUNTS + " accounts with accountId"));
            }
            if (named.stream().anyMatch(a -> !AccountScope.isAccountId(a))) {
                return json(HttpStatus.BAD_REQUEST, Map.of("error", "an accountId is 12 digits"));
            }
            accounts = named;
        } else {
            Optional<String> other = named.stream().filter(a -> !a.equals(c.accountId())).findFirst();
            if (other.isPresent()) {
                return json(HttpStatus.NOT_FOUND, Map.of("error", "account " + other.get() + " not found"));
            }
            accounts = List.of(c.accountId());
        }
        try {
            return json(HttpStatus.OK, work.apply(accounts));
        } catch (InvalidInputException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            body.put("problems", e.problems());
            return json(HttpStatus.BAD_REQUEST, body);
        }
    }

    private Mono<ServerResponse> answer(ServerRequest request, Function<String, Object> work) {
        return answerLater(request, account -> Mono.fromCallable(() -> work.apply(account)));
    }

    /** A call in one account: the token's for a user (another one named is a 404), the one named for the console-internal principal. */
    private Mono<ServerResponse> answerLater(ServerRequest request, Function<String, Mono<?>> work) {
        Optional<DemoCaller> caller = tokens.verify(request.headers().firstHeader(HttpHeaders.AUTHORIZATION));
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
        return Mono.defer(() -> work.apply(account)).flatMap(body -> json(HttpStatus.OK, body))
                .onErrorResume(InvalidInputException.class, e -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("error", e.getMessage());
                    body.put("problems", e.problems());
                    return json(HttpStatus.BAD_REQUEST, body);
                });
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

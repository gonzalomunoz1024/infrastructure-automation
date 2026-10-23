package com.iaf.console.bff.demo.cost.adapters.inbound;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.iaf.console.bff.cost.application.CostConfiguration;
import com.iaf.console.bff.demo.cost.application.DemoCostLedger;
import com.iaf.console.bff.demo.identity.application.DemoCaller;
import com.iaf.console.bff.demo.identity.application.DemoTokens;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * The cost service's contract, served in process for the demo under {@code /demo-cost}: Cost
 * Explorer, a resource's cost, price lists, the chargeback (JSON and CSV), budgets with their
 * periods, the budget check and the estimate. The caller is the BFF's internal JWT, read as the real
 * service reads it: a person reads their token's account (naming another is a 404), the console
 * itself names its accounts with {@code accountId}, repeated.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoCostRoutes {

    private static final String PREFIX = CostConfiguration.DEMO_PATH;
    private static final Pattern ETAG = Pattern.compile("^(?:W/)?\"?(\\d+)\"?$");

    private final DemoCostLedger ledger;
    private final DemoTokens tokens;
    private final ObjectMapper json;

    public DemoCostRoutes(DemoCostLedger ledger, DemoTokens tokens, ObjectMapper json) {
        this.ledger = ledger;
        this.tokens = tokens;
        this.json = json;
    }

    @Bean
    public RouterFunction<ServerResponse> demoCostRouter() {
        return route(GET(PREFIX + "/v1/cost"), r -> read(r, accounts -> ledger.report(accounts, first(r), tags(r))))
                .andRoute(GET(PREFIX + "/v1/wfrn/{wfrn}/cost"), r -> answer(r, c -> {
                    String wfrn = r.pathVariable("wfrn");
                    String account;
                    try {
                        account = Wfrn.parse(wfrn).account();
                    } catch (RuntimeException e) {
                        throw NotFoundException.of("resource", wfrn);
                    }
                    if (!c.internal() && !account.equals(c.accountId())) {
                        throw NotFoundException.of("resource", wfrn);
                    }
                    return ok(ledger.resourceCost(account, wfrn));
                }))
                .andRoute(GET(PREFIX + "/v1/price-lists"), r -> answer(r, c -> ok(ledger.priceLists())))
                .andRoute(POST(PREFIX + "/v1/cost:estimate"), r -> withBody(r, (c, body) -> ok(ledger.estimate(body.path("type").asText(null),
                        body.path("attributes"), body.path("count").asInt(1)))))
                .andRoute(GET(PREFIX + "/v1/chargeback"), r -> read(r, accounts -> ledger.chargeback(accounts, param(r, "month"), param(r, "tag")).report()))
                .andRoute(GET(PREFIX + "/v1/chargeback.csv"), r -> answer(r, c -> {
                    DemoCostLedger.Chargeback cb = ledger.chargeback(accounts(r, c), param(r, "month"), param(r, "tag"));
                    return ServerResponse.ok().contentType(MediaType.parseMediaType("text/csv"))
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"chargeback-" + param(r, "month") + ".csv\"")
                            .header("X-Content-Sha256", DemoCostLedger.sha256(cb.csv())).bodyValue(cb.csv());
                }))
                .andRoute(GET(PREFIX + "/v1/budgets"), r -> answer(r, c -> {
                    List<Map<String, Object>> all = ledger.budgets(account(r, c));
                    Map<String, Object> page = new LinkedHashMap<>();
                    page.put("items", all);
                    page.put("next", null);
                    return ok(page);
                }))
                .andRoute(POST(PREFIX + "/v1/budgets:check"), r -> withBody(r, (c, body) -> {
                    String accountId = body.path("accountId").asText(null);
                    if (accountId == null || (!c.internal() && !accountId.equals(c.accountId()))) {
                        throw NotFoundException.of("account", String.valueOf(accountId));
                    }
                    Map<String, String> tags = new LinkedHashMap<>();
                    body.path("tags").properties().forEach(e -> tags.put(e.getKey(), e.getValue().asText()));
                    BigDecimal monthly = body.path("monthly").isNumber() ? body.get("monthly").decimalValue() : BigDecimal.ZERO;
                    return ok(ledger.check(accountId, tags, monthly.doubleValue()));
                }))
                .andRoute(POST(PREFIX + "/v1/budgets"), r -> withBody(r, (c, body) -> {
                    String named = body.path("scope").path("accountId").asText(null);
                    String accountId = scoped(c, named);
                    Map<String, Object> b = ledger.create(accountId, body, c.subject(), r.headers().firstHeader("Idempotency-Key"));
                    return ServerResponse.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag(b)).bodyValue(b);
                }))
                .andRoute(GET(PREFIX + "/v1/budgets/{budgetId}"), r -> answer(r, c -> {
                    Map<String, Object> b = ledger.budget(account(r, c), r.pathVariable("budgetId"));
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag(b)).bodyValue(b);
                }))
                .andRoute(PUT(PREFIX + "/v1/budgets/{budgetId}"), r -> withBody(r, (c, body) -> {
                    String ifMatch = r.headers().firstHeader(HttpHeaders.IF_MATCH);
                    if (ifMatch == null || ifMatch.isBlank()) {
                        return ServerResponse.status(HttpStatus.PRECONDITION_REQUIRED).contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of("error", "If-Match is required: the ETag the budget was read with"));
                    }
                    Map<String, Object> b = ledger.replace(account(r, c), r.pathVariable("budgetId"), version(ifMatch), body);
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag(b)).bodyValue(b);
                }))
                .andRoute(DELETE(PREFIX + "/v1/budgets/{budgetId}"), r -> answer(r, c -> {
                    String ifMatch = r.headers().firstHeader(HttpHeaders.IF_MATCH);
                    ledger.delete(account(r, c), r.pathVariable("budgetId"), ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.strip()) ? null : version(ifMatch));
                    return ServerResponse.noContent().build();
                }))
                .andRoute(GET(PREFIX + "/v1/budgets/{budgetId}/periods"), r -> answer(r, c -> {
                    Map<String, Object> page = new LinkedHashMap<>();
                    page.put("items", ledger.periods(account(r, c), r.pathVariable("budgetId")));
                    page.put("next", null);
                    return ok(page);
                }));
    }

    // ---- plumbing

    private Mono<ServerResponse> read(ServerRequest request, Function<List<String>, Object> work) {
        return answer(request, c -> ok(work.apply(accounts(request, c))));
    }

    /** The accounts a read names: the person's own (another is a 404), or every {@code accountId} the console names. */
    private static List<String> accounts(ServerRequest request, DemoCaller caller) {
        List<String> named = request.queryParams().getOrDefault("accountId", List.of()).stream().filter(a -> !a.isBlank()).distinct().toList();
        if (caller.internal()) {
            if (named.isEmpty()) {
                throw new InvalidInputException("accountId is required for a call that acts for no account", List.of());
            }
            return named;
        }
        for (String a : named) {
            if (!a.equals(caller.accountId())) {
                throw NotFoundException.of("account", a);
            }
        }
        return List.of(caller.accountId());
    }

    private static String account(ServerRequest request, DemoCaller caller) {
        return scoped(caller, param(request, "accountId"));
    }

    private static String scoped(DemoCaller caller, String named) {
        if (caller.internal()) {
            if (named == null || named.isBlank()) {
                throw new InvalidInputException("accountId is required for a call that acts for no account", List.of());
            }
            return named;
        }
        if (named != null && !named.isBlank() && !named.equals(caller.accountId())) {
            throw NotFoundException.of("account", named);
        }
        return caller.accountId();
    }

    private Mono<ServerResponse> answer(ServerRequest request, Function<DemoCaller, Mono<ServerResponse>> work) {
        Optional<DemoCaller> caller = tokens.verify(request.headers().firstHeader(HttpHeaders.AUTHORIZATION));
        if (caller.isEmpty()) {
            return json(HttpStatus.UNAUTHORIZED, Map.of("error", "invalid or missing token"));
        }
        try {
            return work.apply(caller.get());
        } catch (RuntimeException e) {
            return failure(e);
        }
    }

    private Mono<ServerResponse> withBody(ServerRequest request, java.util.function.BiFunction<DemoCaller, JsonNode, Mono<ServerResponse>> work) {
        return request.bodyToMono(String.class).defaultIfEmpty("{}").flatMap(text -> answer(request, c -> {
            JsonNode body;
            try {
                body = json.readTree(text);
            } catch (java.io.IOException e) {
                body = JsonNodeFactory.instance.objectNode();
            }
            return work.apply(c, body == null ? JsonNodeFactory.instance.objectNode() : body);
        }));
    }

    private static Mono<ServerResponse> failure(RuntimeException e) {
        if (e instanceof InvalidInputException invalid) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", invalid.getMessage());
            body.put("problems", invalid.problems());
            return json(HttpStatus.BAD_REQUEST, body);
        }
        if (e instanceof NotFoundException) {
            return json(HttpStatus.NOT_FOUND, Map.of("error", e.getMessage()));
        }
        if (e instanceof ConflictException conflict) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", conflict.getMessage());
            if (conflict.detail() != null) {
                body.put("detail", conflict.detail());
            }
            return json(HttpStatus.CONFLICT, body);
        }
        if (e instanceof NumberFormatException) {
            return json(HttpStatus.BAD_REQUEST, Map.of("error", "a number was expected: " + e.getMessage()));
        }
        throw e;
    }

    private static Map<String, String> first(ServerRequest request) {
        Map<String, String> out = new LinkedHashMap<>();
        request.queryParams().forEach((name, values) -> {
            if (!values.isEmpty() && !name.startsWith("tag.") && !"accountId".equals(name)) {
                out.put(name, values.get(0));
            }
        });
        return out;
    }

    private static Map<String, String> tags(ServerRequest request) {
        Map<String, String> tags = new LinkedHashMap<>();
        request.queryParams().forEach((name, values) -> {
            if (name.startsWith("tag.") && name.length() > 4 && !values.isEmpty()) {
                tags.put(name.substring(4), values.get(0));
            }
        });
        return tags;
    }

    private static String param(ServerRequest request, String name) {
        return request.queryParam(name).filter(v -> !v.isBlank()).orElse(null);
    }

    private static String etag(Map<String, Object> budget) {
        return "\"" + budget.get("version") + "\"";
    }

    private static long version(String ifMatch) {
        Matcher m = ETAG.matcher(ifMatch.strip());
        if (!m.matches()) {
            throw new InvalidInputException("If-Match is not an ETag of this service", List.of());
        }
        return Long.parseLong(m.group(1));
    }

    private static Mono<ServerResponse> ok(Object body) {
        return json(HttpStatus.OK, body);
    }

    private static Mono<ServerResponse> json(HttpStatus status, Object body) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}

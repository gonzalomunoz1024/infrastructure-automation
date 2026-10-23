package com.iaf.console.bff.demo.drift.adapters.inbound;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.codec.CodecException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.iaf.console.bff.demo.drift.application.DemoDrift;
import com.iaf.console.bff.demo.identity.application.DemoCaller;
import com.iaf.console.bff.demo.identity.application.DemoTokens;
import com.iaf.console.bff.drift.application.DriftConfiguration;
import com.iaf.console.bff.drift.application.DriftProperties;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * The drift service's contract, served in process for the demo under {@code /demo-drift}: the
 * findings (stats too), discovery and the objects no account manages, metrics and alarms, and the
 * recommendations. The caller is the BFF's internal JWT, read as the drift service reads it: a
 * person acts in the token's account, the console itself names one with {@code accountId} (and may
 * leave it out of the stats for every account). Refusals come back in the drift service's shapes:
 * {@code error} with a 400's {@code problems}, and a 409's code in front of its sentence.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoDriftRoutes {

    private static final String PREFIX = DriftConfiguration.DEMO_PATH;
    private static final String ON_BEHALF_OF = "X-On-Behalf-Of";
    private static final String REQUEST_ID = "X-Console-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final DemoDrift drift;
    private final DemoTokens tokens;
    private final DriftProperties properties;

    public DemoDriftRoutes(DemoDrift drift, DemoTokens tokens, DriftProperties properties) {
        this.drift = drift;
        this.tokens = tokens;
        this.properties = properties;
    }

    /** Who calls, and the account the call is for. */
    private record Call(String accountId, DemoCaller caller) {

        /** The person a write is recorded against: the BFF's {@code actor}, else the token's subject. */
        String actor(JsonNode body) {
            String actor = text(body, "actor");
            return actor != null ? actor : caller.subject();
        }
    }

    @Bean
    public RouterFunction<ServerResponse> demoDriftRouter() {
        return RouterFunctions.route()
                // findings: the fixed paths before the finding's
                .GET(PREFIX + "/v1/findings/summary", r -> read(r, call -> ok(drift.summary(call.accountId(), param(r, "region")))))
                .GET(PREFIX + "/v1/findings/stats", r -> serve(r, true, call -> ok(drift.stats(call.accountId(), instant(r, "since"), instant(r, "until")))))
                .GET(PREFIX + "/v1/findings", r -> read(r, call -> ok(page(drift.findings(call.accountId(), param(r, "region"),
                        oneOf(r, "status", List.of("open", "closed", "all")), oneOf(r, "severity", List.of("info", "warning", "critical")),
                        param(r, "type"), param(r, "wfrn"), instant(r, "openedAfter"), limit(r), param(r, "after"))))))
                .GET(PREFIX + "/v1/findings/{findingId}", r -> read(r, call -> ok(drift.finding(call.accountId(), r.pathVariable("findingId")))))
                .POST(PREFIX + "/v1/findings/{findingId}:revert", r -> write(r, (call, body) -> json(HttpStatus.ACCEPTED,
                        drift.revert(call.accountId(), r.pathVariable("findingId"), text(body, "reason"), call.actor(body), header(r, ON_BEHALF_OF),
                                header(r, IDEMPOTENCY_KEY)))))
                .POST(PREFIX + "/v1/findings/{findingId}:accept", r -> write(r, (call, body) -> ok(
                        drift.accept(call.accountId(), r.pathVariable("findingId"), text(body, "reason"), call.actor(body), header(r, IDEMPOTENCY_KEY)))))
                .POST(PREFIX + "/v1/findings/{findingId}:ignore", r -> write(r, (call, body) -> ok(
                        drift.ignore(call.accountId(), r.pathVariable("findingId"), text(body, "reason"), call.actor(body), instant(body, "until"),
                                header(r, IDEMPOTENCY_KEY)))))
                // the shadow-infrastructure finder
                .POST(PREFIX + "/v1/discover", r -> write(r, (call, body) -> json(HttpStatus.ACCEPTED,
                        drift.discover(call.accountId(), required(body, "region"), required(body, "type"), header(r, IDEMPOTENCY_KEY)))))
                .GET(PREFIX + "/v1/discover/{runId}", r -> read(r, call -> ok(drift.run(call.accountId(), r.pathVariable("runId")))))
                .GET(PREFIX + "/v1/unmanaged", r -> read(r, call -> ok(page(drift.unmanaged(call.accountId(), param(r, "region"), param(r, "type"),
                        oneOf(r, "owner", List.of("any", "mine", "unknown")), limit(r), param(r, "after"))))))
                .POST(PREFIX + "/v1/unmanaged:import", r -> write(r, (call, body) -> drift.importObject(call.accountId(), required(body, "region"),
                        required(body, "type"), required(body, "providerId"), text(body, "name"), call.actor(body), header(r, IDEMPOTENCY_KEY),
                        header(r, REQUEST_ID)).flatMap(imported -> json(HttpStatus.CREATED, imported))))
                .POST(PREFIX + "/v1/unmanaged:assign", r -> write(r, (call, body) -> ok(drift.assign(call.accountId(), required(body, "region"),
                        required(body, "type"), required(body, "providerId"), required(body, "accountId"), text(body, "note"), call.actor(body),
                        header(r, IDEMPOTENCY_KEY)))))
                // monitoring
                .GET(PREFIX + "/v1/metrics", r -> read(r, call -> ok(drift.metrics(call.accountId(), requiredParam(r, "wfrn"), instant(r, "from"),
                        instant(r, "to"), duration(r, "step")))))
                .GET(PREFIX + "/v1/alarms", r -> read(r, call -> ok(page(drift.alarms(call.accountId(), param(r, "wfrn"),
                        oneOf(r, "state", List.of("ok", "alarm", "insufficient")), limit(r), param(r, "after"))))))
                .GET(PREFIX + "/v1/alarms/{alarmId}", r -> read(r, call -> ok(drift.alarm(call.accountId(), r.pathVariable("alarmId")))))
                .PUT(PREFIX + "/v1/alarms/{alarmId}", r -> write(r, (call, body) -> {
                    DriftResults.AlarmSaved saved = drift.putAlarm(call.accountId(), r.pathVariable("alarmId"), body);
                    return json(saved.created() ? HttpStatus.CREATED : HttpStatus.OK, saved.alarm());
                }))
                .DELETE(PREFIX + "/v1/alarms/{alarmId}", r -> read(r, call -> {
                    drift.deleteAlarm(call.accountId(), r.pathVariable("alarmId"));
                    return ServerResponse.noContent().build();
                }))
                // rightsizing: the summary before the recommendation's
                .GET(PREFIX + "/v1/recommendations/summary", r -> read(r, call -> ok(drift.recommendationsSummary(call.accountId()))))
                .GET(PREFIX + "/v1/recommendations", r -> read(r, call -> ok(page(drift.recommendations(call.accountId(), param(r, "type"),
                        oneOf(r, "kind", List.of("idle", "oversized")), oneOf(r, "status", List.of("open", "applied", "dismissed", "superseded")),
                        param(r, "wfrn"), limit(r), param(r, "after"))))))
                .GET(PREFIX + "/v1/recommendations/{recommendationId}",
                        r -> read(r, call -> ok(drift.recommendation(call.accountId(), r.pathVariable("recommendationId")))))
                .POST(PREFIX + "/v1/recommendations/{recommendationId}:apply", r -> write(r, (call, body) -> json(HttpStatus.ACCEPTED,
                        drift.apply(call.accountId(), r.pathVariable("recommendationId"), text(body, "reason"), call.actor(body), header(r, ON_BEHALF_OF),
                                header(r, IDEMPOTENCY_KEY)))))
                .POST(PREFIX + "/v1/recommendations/{recommendationId}:dismiss", r -> write(r, (call, body) -> ok(
                        drift.dismiss(call.accountId(), r.pathVariable("recommendationId"), text(body, "reason"), call.actor(body), instant(body, "until"),
                                header(r, IDEMPOTENCY_KEY)))))
                .build();
    }

    // ---- the caller, the account, the refusals

    private Mono<ServerResponse> read(ServerRequest request, Function<Call, Mono<ServerResponse>> work) {
        return serve(request, false, work);
    }

    private Mono<ServerResponse> write(ServerRequest request, BiFunction<Call, JsonNode, Mono<ServerResponse>> work) {
        return serve(request, false, call -> request.bodyToMono(JsonNode.class).defaultIfEmpty(NullNode.getInstance())
                .flatMap(body -> work.apply(call, body)));
    }

    /**
     * Verifies the token and settles the account: a person's is the token's (naming another is
     * 404), the console's is {@code accountId}, which only the stats may leave out.
     */
    private Mono<ServerResponse> serve(ServerRequest request, boolean everyAccount, Function<Call, Mono<ServerResponse>> work) {
        Optional<DemoCaller> verified = tokens.verify(request.headers().firstHeader(HttpHeaders.AUTHORIZATION), properties.serviceName());
        if (verified.isEmpty()) {
            return json(HttpStatus.UNAUTHORIZED, Map.of("error", "invalid or missing token"));
        }
        DemoCaller caller = verified.get();
        String named = param(request, "accountId");
        String account;
        if (caller.internal()) {
            account = named;
            if (account == null && !everyAccount) {
                return json(HttpStatus.BAD_REQUEST, Map.of("error", "accountId is required for the console-internal principal"));
            }
        } else {
            account = caller.accountId();
            if (account == null) {
                return json(HttpStatus.UNAUTHORIZED, Map.of("error", "the token names no account"));
            }
            if (named != null && !named.equals(account)) {
                return json(HttpStatus.NOT_FOUND, Map.of("error", "account " + named + " not found"));
            }
        }
        Call call = new Call(account, caller);
        return Mono.defer(() -> work.apply(call)).onErrorResume(DemoDriftRoutes::refusal);
    }

    private static Mono<ServerResponse> refusal(Throwable error) {
        if (error instanceof InvalidInputException e) {
            List<Map<String, Object>> problems = new ArrayList<>();
            for (Problem p : e.problems()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("code", p.code());
                if (p.field() != null) {
                    one.put("field", p.field());
                }
                one.put("message", p.message());
                problems.add(one);
            }
            return json(HttpStatus.BAD_REQUEST, Map.of("error", e.getMessage(), "problems", problems));
        }
        if (error instanceof ServerWebInputException || error instanceof CodecException) {
            return json(HttpStatus.BAD_REQUEST, Map.of("error", "the body is not JSON"));
        }
        if (error instanceof NotFoundException e) {
            return json(HttpStatus.NOT_FOUND, Map.of("error", e.getMessage()));
        }
        if (error instanceof ConflictException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            if (e.detail() != null) {
                body.put("detail", e.detail());
            }
            return json(HttpStatus.CONFLICT, body);
        }
        return Mono.error(error);
    }

    // ---- reading the request

    private static String param(ServerRequest request, String name) {
        return request.queryParam(name).filter(v -> !v.isBlank()).orElse(null);
    }

    private static String requiredParam(ServerRequest request, String name) {
        String value = param(request, name);
        if (value == null) {
            throw invalid("required", name, name + " is required");
        }
        return value;
    }

    private static String header(ServerRequest request, String name) {
        String value = request.headers().firstHeader(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String oneOf(ServerRequest request, String name, List<String> allowed) {
        String value = param(request, name);
        if (value != null && !allowed.contains(value)) {
            throw invalid("enum", name, name + " is one of " + String.join(", ", allowed));
        }
        return value;
    }

    private static int limit(ServerRequest request) {
        String value = param(request, "limit");
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value.strip());
            if (limit < 1) {
                throw invalid("range", "limit", "limit is at least 1");
            }
            return Math.min(limit, MAX_LIMIT);
        } catch (NumberFormatException e) {
            throw invalid("type", "limit", "limit is a number");
        }
    }

    private static Instant instant(ServerRequest request, String name) {
        return instant(name, param(request, name));
    }

    private static Instant instant(JsonNode body, String name) {
        return instant(name, text(body, name));
    }

    private static Instant instant(String name, String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(value.strip());
            } catch (DateTimeParseException again) {
                throw invalid("format", name, name + " is a date-time, like 2026-05-01T00:00:00Z");
            }
        }
    }

    private static Duration duration(ServerRequest request, String name) {
        String value = param(request, name);
        if (value == null) {
            return null;
        }
        try {
            return Duration.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw invalid("format", name, name + " is an ISO-8601 duration, like PT5M");
        }
    }

    private static String text(JsonNode body, String name) {
        JsonNode value = body == null ? null : body.get(name);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static String required(JsonNode body, String name) {
        String value = text(body, name);
        if (value == null) {
            throw invalid("required", name, name + " is required");
        }
        return value;
    }

    private static InvalidInputException invalid(String code, String field, String message) {
        return new InvalidInputException(message, List.of(Problem.error(code, "/" + field, message)));
    }

    // ---- answering

    private static Map<String, Object> page(Page<?> page) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.items());
        body.put("next", page.next());
        return body;
    }

    private static Mono<ServerResponse> ok(Object body) {
        return json(HttpStatus.OK, body);
    }

    private static Mono<ServerResponse> json(HttpStatus status, Object body) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}

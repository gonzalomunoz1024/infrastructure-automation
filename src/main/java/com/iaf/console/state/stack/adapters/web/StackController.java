package com.iaf.console.state.stack.adapters.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.auth.domain.ConsolePrincipal;
import com.iaf.console.state.boot.StateProperties;
import com.iaf.console.state.inventory.application.IdempotentCommands;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.stack.application.BlueprintCatalog;
import com.iaf.console.state.stack.application.StackEventPublisher;
import com.iaf.console.state.stack.application.StackQueries;
import com.iaf.console.state.stack.application.Stacks;
import com.iaf.console.state.stack.domain.Blueprint;
import com.iaf.console.state.stack.domain.BlueprintParameter;
import com.iaf.console.state.stack.domain.BlueprintResource;
import com.iaf.console.state.stack.domain.Stack;
import com.iaf.console.state.stack.domain.StackQuery;
import com.iaf.console.state.stack.domain.StackState;

import reactor.core.publisher.Mono;

/**
 * The blueprint and stack routes of {@code contracts/openapi/console-state-service.yaml} 1.2.0
 * (hub decision 33). A stack's wfrn in a path arrives percent-encoded and decoded by the framework;
 * its account is the scope, so another account's stack is a 404.
 */
@RestController
public final class StackController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String CONSOLE_REQUEST_ID = "X-Console-Request-Id";

    private final Stacks stacks;
    private final StackQueries queries;
    private final BlueprintCatalog catalog;
    private final IdempotentCommands idempotent;
    private final String defaultRegion;

    public StackController(Stacks stacks, StackQueries queries, BlueprintCatalog catalog, IdempotentCommands idempotent, StateProperties properties) {
        this.stacks = stacks;
        this.queries = queries;
        this.catalog = catalog;
        this.idempotent = idempotent;
        this.defaultRegion = properties.region();
    }

    /** The contract's page: {@code next} is present on every page, null on the last. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Listing<T>(List<T> items, String next) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BlueprintSummary(String name, String version, String title, String description, String owner, List<String> tags,
            Map<String, Integer> resources) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BlueprintView(String name, String version, String title, String description, String owner, List<String> tags,
            Map<String, BlueprintParameter> parameters, Map<String, ResourceView> resources, Map<String, ?> outputs, List<List<String>> waves) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceView(String type, String service, String title, ObjectNode input, Map<String, String> tags, List<String> dependsOn,
            String deletionPolicy, ObjectNode deleteInput) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanRequest(String name, ObjectNode parameters) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LaunchRequest(String blueprint, String version, String name, ObjectNode parameters, Map<String, String> tags, String actor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeardownRequest(Long expectedVersion, String reason, List<String> retain, String actor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommandRequest(Long expectedVersion, String actor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepFailureRequest(Integer attempt, String action, String code, String message, String actor) {
    }

    // blueprints

    @GetMapping("/v1/blueprints")
    public Listing<BlueprintSummary> blueprints() {
        return new Listing<>(catalog.all().stream().map(bp -> new BlueprintSummary(bp.name(), bp.version(), bp.title(), bp.description(), bp.owner(),
                bp.tags(), bp.creates())).toList(), null);
    }

    @GetMapping("/v1/blueprints/{name}")
    public Mono<BlueprintView> blueprint(ServerWebExchange exchange, @PathVariable String name) {
        Blueprint bp = catalog.require(name);
        return stacks.servicesOf(bp).map(services -> {
            Map<String, ResourceView> resources = new LinkedHashMap<>();
            for (Map.Entry<String, BlueprintResource> e : bp.resources().entrySet()) {
                BlueprintResource r = e.getValue();
                resources.put(e.getKey(), new ResourceView(r.type(), services.get(r.type()), r.title(), r.input(), r.tags(), r.dependsOn(),
                        r.deletionPolicy().wire(), r.deleteInput()));
            }
            return new BlueprintView(bp.name(), bp.version(), bp.title(), bp.description(), bp.owner(), bp.tags(), bp.parameters(), resources,
                    bp.outputs(), bp.waves());
        });
    }

    @PostMapping("/v1/blueprints/{name}:plan")
    public Mono<Stacks.Plan> plan(ServerWebExchange exchange, @PathVariable String name, @RequestBody PlanRequest body) {
        AccountScope scope = scope(exchange, null);
        return stacks.plan(scope, name, body.name(), body.parameters());
    }

    // stacks

    @GetMapping("/v1/stacks")
    public Mono<Listing<Stack>> list(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String blueprint,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        ConsolePrincipal principal = principal(exchange);
        AccountScope scope = principal.scope(accountId, principal.region() != null ? principal.region() : defaultRegion);
        StackState s = null;
        if (state != null) {
            s = StackState.of(state);
            if (s == null) {
                throw new InvalidInputException("unknown state", List.of(Problem.error("enum", "/state", "one of: pending, active, failed, deleting, deleted")));
            }
        }
        StackQuery query = new StackQuery(s, blueprint, name, CursorPaging.pageSize(limit), after == null ? null : CursorPaging.decode(after));
        return queries.list(scope, query).map(page -> new Listing<>(page.items(), page.next()));
    }

    @PostMapping("/v1/stacks")
    public Mono<ResponseEntity<ObjectNode>> launch(ServerWebExchange exchange, @RequestBody LaunchRequest body,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        ConsolePrincipal principal = principal(exchange);
        if (principal.internal()) {
            throw new InvalidInputException("a stack is launched for an account", List.of(Problem.error("account.required", null,
                    "the caller acts for no account; the BFF launches with the account's token")));
        }
        AccountScope scope = scope(exchange, null);
        String actor = principal.actor(body.actor());
        return command(scope, key, body, HttpStatus.CREATED, () -> stacks.launch(scope, new Stacks.Launch(body.blueprint(), body.version(), body.name(),
                body.parameters(), body.tags(), actor, requestId)));
    }

    @GetMapping("/v1/stacks/{wfrn}")
    public Mono<ResponseEntity<Stack>> get(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return queries.get(scopeOf(exchange, wfrn), wfrn).map(s -> ifNoneMatch != null && ifNoneMatch.trim().equals(s.etag())
                ? ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(s.etag()).<Stack>build()
                : ResponseEntity.ok().eTag(s.etag()).body(s));
    }

    @GetMapping("/v1/stacks/{wfrn}/events")
    public Mono<Listing<EventEnvelope>> events(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        long afterVersion = 0;
        if (after != null && !after.isBlank()) {
            try {
                afterVersion = Long.parseLong(after.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("after is a version number on a stack's log");
            }
        }
        return queries.events(scopeOf(exchange, wfrn), wfrn, afterVersion, limit)
                .map(page -> new Listing<>(page.items().stream().map(StackEventPublisher::envelope).toList(), page.next()));
    }

    @PostMapping("/v1/stacks/{wfrn}:teardown")
    public Mono<ResponseEntity<ObjectNode>> teardown(ServerWebExchange exchange, @PathVariable String wfrn, @RequestBody TeardownRequest body,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        AccountScope scope = scopeOf(exchange, wfrn);
        String actor = principal(exchange).actor(body.actor());
        return command(scope, key, body, HttpStatus.OK, () -> expect(scope, wfrn, body.expectedVersion())
                .then(stacks.teardown(scope, wfrn, body.reason(), body.retain() == null ? List.of() : new ArrayList<>(body.retain()), actor, requestId)));
    }

    @PostMapping("/v1/stacks/{wfrn}:resume")
    public Mono<ResponseEntity<ObjectNode>> resume(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestBody(required = false) CommandRequest body,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        AccountScope scope = scopeOf(exchange, wfrn);
        CommandRequest b = body == null ? new CommandRequest(null, null) : body;
        String actor = principal(exchange).actor(b.actor());
        return command(scope, key, b, HttpStatus.OK, () -> expect(scope, wfrn, b.expectedVersion()).then(stacks.resume(scope, wfrn, actor, requestId)));
    }

    @PostMapping("/v1/stacks/{wfrn}/steps/{step}:report-failure")
    public Mono<ResponseEntity<ObjectNode>> reportFailure(ServerWebExchange exchange, @PathVariable String wfrn, @PathVariable String step,
            @RequestBody StepFailureRequest body,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        AccountScope scope = scopeOf(exchange, wfrn);
        String actor = principal(exchange).actor(body.actor());
        return command(scope, null, body, HttpStatus.OK, () -> stacks.reportFailure(scope, wfrn, step, body.attempt(), body.action(), body.code(),
                body.message(), actor, requestId));
    }

    // helpers

    /** 409 {@code version.mismatch} when the caller saw another version than the stack's. */
    private Mono<Void> expect(AccountScope scope, String wfrn, Long expectedVersion) {
        if (expectedVersion == null) {
            return Mono.empty();
        }
        return queries.get(scope, wfrn).flatMap(s -> s.version() == expectedVersion ? Mono.<Void>empty()
                : Mono.error(new com.iaf.console.kit.errors.ConflictException("version.mismatch: the stack is at version " + s.version(),
                        Map.of("version", s.version()))));
    }

    private Mono<ResponseEntity<ObjectNode>> command(AccountScope scope, String key, Object body, HttpStatus status, Supplier<Mono<Stack>> run) {
        String canonical;
        try {
            canonical = Json.MAPPER.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the body cannot be serialised");
        }
        return idempotent.runJson(scope, key, canonical, status.value(), () -> run.get().map(Json::objectNode))
                .map(outcome -> {
                    ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.status());
                    if (outcome.replay()) {
                        response.header("Idempotent-Replay", "true");
                    }
                    String version = Json.text(outcome.body(), "version");
                    if (version != null) {
                        response.eTag("\"" + version + "\"");
                    }
                    return response.body(outcome.body());
                });
    }

    private AccountScope scope(ServerWebExchange exchange, String accountId) {
        ConsolePrincipal principal = principal(exchange);
        return principal.scope(accountId, principal.region() != null ? principal.region() : defaultRegion);
    }

    private AccountScope scopeOf(ServerWebExchange exchange, String wfrn) {
        ResourceIdentity id;
        try {
            id = ResourceIdentity.of(wfrn);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("not a wfrn", List.of(Problem.error("wfrn", "/wfrn", e.getMessage())));
        }
        return principal(exchange).scope(id.accountId(), id.region());
    }

    private static ConsolePrincipal principal(ServerWebExchange exchange) {
        ConsolePrincipal principal = exchange.getAttribute(ConsolePrincipal.ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("no principal on the exchange: the authentication filter did not run");
        }
        return principal;
    }
}

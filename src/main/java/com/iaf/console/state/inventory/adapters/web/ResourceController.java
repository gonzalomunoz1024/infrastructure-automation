package com.iaf.console.state.inventory.adapters.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.auth.domain.ConsolePrincipal;
import com.iaf.console.state.boot.StateProperties;
import com.iaf.console.state.inventory.application.IdempotentCommands;
import com.iaf.console.state.inventory.application.InventoryQueries;
import com.iaf.console.state.inventory.application.InventoryService;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.domain.ResourceState;
import com.iaf.console.state.inventory.domain.TagRules;
import com.iaf.console.state.inventory.domain.TombstoneException;
import com.iaf.console.wfrn.WfrnPattern;

import reactor.core.publisher.Mono;

/**
 * The contract's HTTP surface ({@code contracts/openapi/console-state-service.yaml}): the list,
 * the two opening commands, one resource by wfrn (with its ETag and its log) and the commands on
 * it. A wfrn in a path arrives percent-encoded and is decoded by the framework; it is checked and
 * its account is the scope, so another account's name is a 404.
 */
@RestController
public final class ResourceController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String CONSOLE_REQUEST_ID = "X-Console-Request-Id";

    private final InventoryService inventory;
    private final InventoryQueries queries;
    private final IdempotentCommands idempotent;
    private final String defaultRegion;

    public ResourceController(InventoryService inventory, InventoryQueries queries, IdempotentCommands idempotent, StateProperties properties) {
        this.inventory = inventory;
        this.queries = queries;
        this.idempotent = idempotent;
        this.defaultRegion = properties.region();
    }

    // the list

    /** The contract's page: {@code next} is present on every page, null on the last. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Listing<T>(List<T> items, String next) {
        static <T> Listing<T> of(Page<T> page) {
            return new Listing<>(page.items(), page.next());
        }
    }

    @GetMapping("/v1/resources")
    public Mono<Listing<Resource>> list(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String wfrn,
            @RequestParam(required = false) Instant updatedAfter,
            @RequestParam(required = false) Boolean drift,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        ConsolePrincipal principal = principal(exchange);
        AccountScope scope = principal.scope(accountId, region != null ? region : principal.region() != null ? principal.region() : defaultRegion);
        ResourceState s = null;
        if (state != null) {
            s = ResourceState.of(state);
            if (s == null) {
                throw new InvalidInputException("unknown state", List.of(Problem.error("enum", "/state", "one of: pending, active, updating, deleting, deleted, failed")));
            }
        }
        if (wfrn != null) {
            WfrnPattern.parse(wfrn);
        }
        Map<String, String> tags = new LinkedHashMap<>();
        exchange.getRequest().getQueryParams().forEach((k, v) -> {
            if (k.startsWith("tag.") && !v.isEmpty()) {
                String tag = k.substring(4);
                if (!TagRules.NAME.matcher(tag).matches()) {
                    throw new InvalidInputException("bad tag filter", List.of(Problem.error("tag.name", "/" + k, "a tag name is [a-z0-9-]{1,32}")));
                }
                tags.put(tag, v.get(0));
            }
        });
        ResourceQuery query = new ResourceQuery(type, s, name, tags, wfrn, updatedAfter, drift, ResourceQuery.Sort.of(sort),
                ResourceQuery.limitOf(limit), after == null ? null : CursorPaging.decode(after));
        return queries.list(scope, query).map(Listing::of);
    }

    // opening commands

    @PostMapping("/v1/resources:mint")
    public Mono<ResponseEntity<ObjectNode>> mint(ServerWebExchange exchange, @RequestBody Requests.MintRequest body,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        ConsolePrincipal principal = principal(exchange);
        if (principal.internal()) {
            throw new InvalidInputException("a resource is minted for an account", List.of(Problem.error("account.required", null,
                    "the caller acts for no account; the BFF mints with the account's token")));
        }
        AccountScope scope = principal.scope(null, body.region() != null ? body.region() : defaultRegion);
        return command(scope, key, body, HttpStatus.CREATED, () -> inventory.mint(scope, body.command(principal.subject(), requestId)));
    }

    @PostMapping("/v1/resources:import")
    public Mono<ResponseEntity<ObjectNode>> importResource(ServerWebExchange exchange, @RequestBody Requests.ImportRequest body,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        ConsolePrincipal principal = principal(exchange);
        String account = null;
        if (body.wfrn() != null && !body.wfrn().isBlank()) {
            account = ResourceIdentity.of(body.wfrn()).accountId();
        }
        AccountScope scope = principal.scope(account, body.region() != null ? body.region() : defaultRegion);
        return command(scope, key, body, HttpStatus.CREATED, () -> inventory.importResource(scope, body.command(principal.subject(), requestId)));
    }

    // one resource

    @GetMapping("/v1/wfrn/{wfrn}")
    public Mono<ResponseEntity<Object>> get(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestParam(required = false) Long asOf,
            @RequestParam(required = false) String reveal,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        AccountScope scope = scopeOf(exchange, wfrn);
        return queries.get(scope, wfrn, asOf, revealing(exchange, reveal)).map(r -> {
            if (r.deleted() && asOf == null) {
                return ResponseEntity.status(HttpStatus.GONE).<Object>body(r.tombstone());
            }
            if (ifNoneMatch != null && ifNoneMatch.trim().equals(r.etag())) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(r.etag()).<Object>build();
            }
            return ResponseEntity.ok().eTag(r.etag()).<Object>body(r);
        });
    }

    @GetMapping("/v1/wfrn/{wfrn}/events")
    public Mono<Listing<EventEnvelope>> events(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String reveal) {
        AccountScope scope = scopeOf(exchange, wfrn);
        long afterVersion = 0;
        if (after != null && !after.isBlank()) {
            try {
                afterVersion = Long.parseLong(after.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("after is a version number on a resource's log");
            }
        }
        return queries.events(scope, wfrn, afterVersion, ResourceQuery.limitOf(limit), revealing(exchange, reveal)).map(Listing::of);
    }

    @PostMapping("/v1/wfrn/{wfrn}:request-update")
    public Mono<ResponseEntity<ObjectNode>> requestUpdate(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestBody Requests.UpdateRequest body, @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        return on(exchange, wfrn, key, body, (scope, actor) -> inventory.requestUpdate(scope, wfrn, body.command(actor, requestId)));
    }

    @PostMapping("/v1/wfrn/{wfrn}:request-delete")
    public Mono<ResponseEntity<ObjectNode>> requestDelete(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestBody Requests.DeleteRequest body, @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        return on(exchange, wfrn, key, body, (scope, actor) -> inventory.requestDelete(scope, wfrn, body.command(actor, requestId)));
    }

    @PostMapping("/v1/wfrn/{wfrn}:tag")
    public Mono<ResponseEntity<ObjectNode>> tag(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestBody Requests.TagRequest body, @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        return on(exchange, wfrn, key, body, (scope, actor) -> inventory.tag(scope, wfrn, body.command(actor, requestId)));
    }

    @PostMapping("/v1/wfrn/{wfrn}:record-observation")
    public Mono<ResponseEntity<ObjectNode>> recordObservation(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestBody Requests.ObservationRequest body, @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        return on(exchange, wfrn, key, body, (scope, actor) -> inventory.recordObservation(scope, wfrn, body.command(actor, requestId)));
    }

    @PostMapping("/v1/wfrn/{wfrn}:record-drift")
    public Mono<ResponseEntity<ObjectNode>> recordDrift(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestBody Requests.DriftRequest body, @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        return on(exchange, wfrn, key, body, (scope, actor) -> inventory.recordDrift(scope, wfrn, body.command(actor, requestId)));
    }

    @PostMapping("/v1/wfrn/{wfrn}:resolve-drift")
    public Mono<ResponseEntity<ObjectNode>> resolveDrift(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestBody Requests.ResolveDriftRequest body, @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(value = CONSOLE_REQUEST_ID, required = false) String requestId) {
        return on(exchange, wfrn, key, body, (scope, actor) -> inventory.resolveDrift(scope, wfrn, body.command(actor, requestId)));
    }

    // helpers

    private interface Command extends java.util.function.BiFunction<AccountScope, String, Mono<Resource>> {
    }

    private Mono<ResponseEntity<ObjectNode>> on(ServerWebExchange exchange, String wfrn, String key, Object body, Command command) {
        ConsolePrincipal principal = principal(exchange);
        AccountScope scope = scopeOf(exchange, wfrn);
        return command(scope, key, body, HttpStatus.OK, () -> command.apply(scope, principal.subject()));
    }

    private Mono<ResponseEntity<ObjectNode>> command(AccountScope scope, String key, Object body, HttpStatus status, Supplier<Mono<Resource>> run) {
        String canonical;
        try {
            canonical = Json.MAPPER.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the body cannot be serialised");
        }
        return idempotent.run(scope, key, canonical, status.value(), run)
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
                })
                .onErrorMap(TombstoneException.class, Function.identity());
    }

    /** The scope a wfrn is read in: its own account, which must be the caller's unless the caller is internal. */
    private AccountScope scopeOf(ServerWebExchange exchange, String wfrn) {
        ConsolePrincipal principal = principal(exchange);
        ResourceIdentity id = ResourceIdentity.of(wfrn);
        return principal.scope(id.accountId(), id.region());
    }

    /**
     * {@code reveal=sensitive} counts for the console's own services and for a call the BFF makes for an
     * account admin; for anyone else it is ignored and the answer is masked, as the contract says.
     */
    private static boolean revealing(ServerWebExchange exchange, String reveal) {
        return "sensitive".equals(reveal) && principal(exchange).mayReveal();
    }

    static ConsolePrincipal principal(ServerWebExchange exchange) {
        ConsolePrincipal principal = exchange.getAttribute(ConsolePrincipal.ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("no principal on the exchange: the authentication filter did not run");
        }
        return principal;
    }
}

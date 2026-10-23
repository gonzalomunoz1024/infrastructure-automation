package com.iaf.console.drift.drift.adapters.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;

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
import com.iaf.console.drift.drift.application.DriftService;
import com.iaf.console.drift.drift.application.FindingQueries;
import com.iaf.console.drift.auth.domain.ConsolePrincipal;
import com.iaf.console.drift.drift.domain.Decision;
import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.FindingQuery;
import com.iaf.console.drift.drift.domain.FindingsSummary;
import com.iaf.console.drift.drift.domain.Severity;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * The findings API of {@code contracts/openapi/console-drift-service.yaml}: the list, the
 * summary, one finding, and the three decisions. A revert relays the BFF's
 * {@code X-On-Behalf-Of} for the person, so the job names them.
 */
@RestController
public final class FindingController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REQUEST_ID = "X-Console-Request-Id";
    static final String ON_BEHALF_OF = "X-On-Behalf-Of";

    private final FindingQueries queries;
    private final DriftService decisions;

    public FindingController(FindingQueries queries, DriftService decisions) {
        this.queries = queries;
        this.decisions = decisions;
    }

    @GetMapping("/v1/findings")
    public Mono<Listing<Finding>> list(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String wfrn,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String openedAfter,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        AccountScope scope = Principals.scope(exchange, accountId, region);
        if (wfrn != null && wfrn.indexOf('*') < 0 && wfrn.indexOf('?') < 0) {
            Wfrn.parse(wfrn);
        }
        FindingQuery query = new FindingQuery(wfrn, status, severity(severity), type,
                region != null ? region : scope.region(), instant(openedAfter, "openedAfter"), CursorPaging.pageSize(limit),
                after == null || after.isBlank() ? null : CursorPaging.decode(after));
        return queries.list(scope, query).map(Listing::of);
    }

    /** The severity filter; a value the API does not know is a 400, never an empty page. */
    static Severity severity(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Severity s = Severity.of(value.trim());
        if (s == null) {
            throw new IllegalArgumentException("severity is one of: info, warning, critical");
        }
        return s;
    }

    @GetMapping("/v1/findings/summary")
    public Mono<FindingsSummary> summary(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String region) {
        AccountScope scope = Principals.scope(exchange, accountId, region);
        return queries.summary(scope, region != null ? region : scope.region());
    }

    @GetMapping("/v1/findings/{findingId}")
    public Mono<Finding> get(ServerWebExchange exchange, @PathVariable String findingId, @RequestParam(required = false) String accountId) {
        ConsolePrincipal principal = Principals.principal(exchange);
        if (principal.internal() && accountId == null) {
            return queries.getAny(findingId);
        }
        return queries.get(Principals.scope(exchange, accountId, null), findingId);
    }

    @PostMapping("/v1/findings/{findingId}:revert")
    public Mono<ResponseEntity<ObjectNode>> revert(ServerWebExchange exchange, @PathVariable String findingId,
            @RequestParam(required = false) String accountId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestHeader(value = REQUEST_ID, required = false) String requestId,
            @RequestHeader(value = ON_BEHALF_OF, required = false) String onBehalfOf,
            @RequestBody(required = false) Requests.DecisionRequest body) {
        ConsolePrincipal principal = Principals.principal(exchange);
        Decision decision = body == null ? Decision.NONE : body.decision();
        return Principals.findingScope(exchange, findingId, accountId, queries)
                .flatMap(scope -> decisions.revert(scope, findingId, decision, idempotencyKey, requestId, onBehalfOf, Principals.person(principal)))
                .map(Principals::respond);
    }

    @PostMapping("/v1/findings/{findingId}:accept")
    public Mono<ResponseEntity<ObjectNode>> accept(ServerWebExchange exchange, @PathVariable String findingId,
            @RequestParam(required = false) String accountId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestHeader(value = REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) Requests.DecisionRequest body) {
        Decision decision = body == null ? Decision.NONE : body.decision();
        return Principals.findingScope(exchange, findingId, accountId, queries)
                .flatMap(scope -> decisions.accept(scope, findingId, decision, idempotencyKey, requestId))
                .map(Principals::respond);
    }

    @PostMapping("/v1/findings/{findingId}:ignore")
    public Mono<ResponseEntity<ObjectNode>> ignore(ServerWebExchange exchange, @PathVariable String findingId,
            @RequestParam(required = false) String accountId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestHeader(value = REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) Requests.IgnoreRequest body) {
        Decision decision = body == null ? Decision.NONE : body.decision();
        Instant until = body == null ? null : body.until();
        return Principals.findingScope(exchange, findingId, accountId, queries)
                .flatMap(scope -> decisions.ignore(scope, findingId, decision, until, idempotencyKey, requestId))
                .map(Principals::respond);
    }

    static Instant instant(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " is an RFC 3339 time, such as 2026-10-23T12:00:00Z");
        }
    }
}

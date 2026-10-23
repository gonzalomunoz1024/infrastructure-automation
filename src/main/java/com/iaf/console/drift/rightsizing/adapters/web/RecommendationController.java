package com.iaf.console.drift.rightsizing.adapters.web;

import java.time.Instant;

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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.auth.application.WriteGuard;
import com.iaf.console.drift.auth.domain.ConsolePrincipal;
import com.iaf.console.drift.drift.application.IdempotentCommands;
import com.iaf.console.drift.drift.domain.Decision;
import com.iaf.console.drift.drift.ports.outbound.OnBehalfOfPort;
import com.iaf.console.drift.rightsizing.application.RecommendationQueries;
import com.iaf.console.drift.rightsizing.application.RecommendationService;
import com.iaf.console.drift.rightsizing.application.UtilisationPass;
import com.iaf.console.drift.rightsizing.application.UtilisationReport;
import com.iaf.console.drift.rightsizing.domain.Recommendation;
import com.iaf.console.drift.rightsizing.domain.RecommendationKind;
import com.iaf.console.drift.rightsizing.domain.RecommendationQuery;
import com.iaf.console.drift.rightsizing.domain.RecommendationStatus;
import com.iaf.console.drift.rightsizing.domain.RecommendationsSummary;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * The rightsizing API of {@code contracts/openapi/console-drift-service.yaml}: the account's
 * recommendations with the largest saving first, the summary, one recommendation, the two decisions
 * (an apply relays the BFF's {@code X-On-Behalf-Of} for the person, so the job names them), and for
 * operators the utilisation pass now.
 */
@RestController
public final class RecommendationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REQUEST_ID = "X-Console-Request-Id";
    static final String ON_BEHALF_OF = "X-On-Behalf-Of";
    /** Set on the answer of a decision replayed under its Idempotency-Key. */
    static final String REPLAY_HEADER = "Idempotent-Replay";
    /** The region a scope carries when the token names none; the stores scope by account alone. */
    static final String DEFAULT_REGION = "global";

    /** {@code Decision} with the dismissal's {@code until}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DismissRequest(String reason, String actor, Instant until) {
    }

    private final RecommendationQueries queries;
    private final RecommendationService decisions;
    private final UtilisationPass pass;
    private final WriteGuard guard;

    public RecommendationController(RecommendationQueries queries, RecommendationService decisions, UtilisationPass pass, WriteGuard guard) {
        this.queries = queries;
        this.decisions = decisions;
        this.pass = pass;
        this.guard = guard;
    }

    @GetMapping("/v1/recommendations")
    public Mono<Listing<Recommendation>> list(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String wfrn,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        AccountScope scope = scope(exchange, accountId);
        RecommendationQuery query = new RecommendationQuery(type == null || type.isBlank() ? null : type.trim(), kind(kind), status(status),
                wfrn == null || wfrn.isBlank() ? null : Wfrn.parse(wfrn.trim()).format(), CursorPaging.pageSize(limit),
                after == null || after.isBlank() ? null : CursorPaging.decode(after));
        return queries.list(scope, query).map(Listing::of);
    }

    @GetMapping("/v1/recommendations/summary")
    public Mono<RecommendationsSummary> summary(ServerWebExchange exchange, @RequestParam(required = false) String accountId) {
        return queries.summary(scope(exchange, accountId));
    }

    @GetMapping("/v1/recommendations/{recommendationId}")
    public Mono<Recommendation> get(ServerWebExchange exchange, @PathVariable String recommendationId, @RequestParam(required = false) String accountId) {
        ConsolePrincipal principal = principal(exchange);
        if (principal.internal() && accountId == null) {
            return queries.getAny(recommendationId);
        }
        return queries.get(scope(exchange, accountId), recommendationId);
    }

    @PostMapping("/v1/recommendations/{recommendationId}:apply")
    public Mono<ResponseEntity<ObjectNode>> apply(ServerWebExchange exchange, @PathVariable String recommendationId,
            @RequestParam(required = false) String accountId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestHeader(value = REQUEST_ID, required = false) String requestId,
            @RequestHeader(value = ON_BEHALF_OF, required = false) String onBehalfOf,
            @RequestBody(required = false) Decision body) {
        ConsolePrincipal principal = principal(exchange);
        Decision decision = new Decision(body == null ? null : body.reason(), WriteGuard.actor(principal, body == null ? null : body.actor()));
        return decidable(exchange, principal, recommendationId, accountId, true)
                .flatMap(scope -> decisions.apply(scope, recommendationId, decision, idempotencyKey, requestId, onBehalfOf, person(principal)))
                .map(RecommendationController::respond);
    }

    @PostMapping("/v1/recommendations/{recommendationId}:dismiss")
    public Mono<ResponseEntity<ObjectNode>> dismiss(ServerWebExchange exchange, @PathVariable String recommendationId,
            @RequestParam(required = false) String accountId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) DismissRequest body) {
        ConsolePrincipal principal = principal(exchange);
        Decision decision = new Decision(body == null ? null : body.reason(), WriteGuard.actor(principal, body == null ? null : body.actor()));
        Instant until = body == null ? null : body.until();
        return decidable(exchange, principal, recommendationId, accountId, false)
                .flatMap(scope -> decisions.dismiss(scope, recommendationId, decision, until, idempotencyKey))
                .map(RecommendationController::respond);
    }

    @PostMapping("/internal/utilisation")
    public Mono<UtilisationReport> utilisation(ServerWebExchange exchange, @RequestParam(required = false) String accountId) {
        if (!principal(exchange).internal()) {
            throw new NotFoundException("not found");
        }
        if (accountId != null && !AccountScope.isAccountId(accountId)) {
            throw new IllegalArgumentException("accountId is 12 digits");
        }
        return pass.run(accountId, true);
    }

    /** The kind filter; a word the API does not know is a 400, never an empty page. */
    static RecommendationKind kind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        RecommendationKind k = RecommendationKind.of(value.trim());
        if (k == null) {
            throw new IllegalArgumentException("kind is one of: idle, oversized");
        }
        return k;
    }

    /** The status filter, {@code open} when absent; a word the API does not know is a 400. */
    static RecommendationStatus status(String value) {
        if (value == null || value.isBlank()) {
            return RecommendationStatus.OPEN;
        }
        RecommendationStatus s = RecommendationStatus.of(value.trim());
        if (s == null) {
            throw new IllegalArgumentException("status is one of: open, applied, dismissed, superseded");
        }
        return s;
    }

    private static ConsolePrincipal principal(ServerWebExchange exchange) {
        ConsolePrincipal principal = exchange.getAttribute(ConsolePrincipal.ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("no principal on the exchange: the authentication filter did not run");
        }
        return principal;
    }

    /** The caller's own account, or the named one for the console's own services (which must name one). */
    private static AccountScope scope(ServerWebExchange exchange, String accountId) {
        if (accountId != null && !AccountScope.isAccountId(accountId)) {
            throw new IllegalArgumentException("accountId is 12 digits");
        }
        ConsolePrincipal principal = principal(exchange);
        return principal.scope(accountId, principal.region() == null ? DEFAULT_REGION : null);
    }

    /** The scope of a decision: the caller's account, or for the console's own services that name none, the recommendation's. */
    /**
     * The scope of a decision on a recommendation, once the caller may change its resource: applying
     * an idle one deletes the resource ({@code <service>:delete}), anything else updates it.
     */
    private Mono<AccountScope> decidable(ServerWebExchange exchange, ConsolePrincipal principal, String recommendationId, String accountId,
            boolean applying) {
        return recommendationScope(exchange, recommendationId, accountId).flatMap(scope -> principal.internal() ? Mono.just(scope)
                : queries.get(scope, recommendationId)
                        .flatMap(r -> guard.require(principal, applying && r.kind() == RecommendationKind.IDLE ? "delete" : "update", r.wfrn()))
                        .thenReturn(scope));
    }

    private Mono<AccountScope> recommendationScope(ServerWebExchange exchange, String recommendationId, String accountId) {
        ConsolePrincipal principal = principal(exchange);
        if (principal.internal() && accountId == null) {
            return queries.getAny(recommendationId).map(r -> AccountScope.of(r.accountId(), r.region(), principal.subject()));
        }
        return Mono.fromCallable(() -> scope(exchange, accountId));
    }

    /** The person a change runs for: the caller when it is a person of the account; none for a service. */
    private static OnBehalfOfPort.Person person(ConsolePrincipal principal) {
        if (principal.internal()) {
            return null;
        }
        return new OnBehalfOfPort.Person(principal.subject(), null, null, principal.groups());
    }

    private static ResponseEntity<ObjectNode> respond(IdempotentCommands.Outcome outcome) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(outcome.status());
        if (outcome.replay()) {
            builder.header(REPLAY_HEADER, "true");
        }
        return builder.body(outcome.body());
    }
}

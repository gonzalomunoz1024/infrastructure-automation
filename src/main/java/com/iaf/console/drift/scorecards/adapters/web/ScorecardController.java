package com.iaf.console.drift.scorecards.adapters.web;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
import com.iaf.console.drift.auth.domain.ConsolePrincipal;
import com.iaf.console.drift.drift.application.IdempotentCommands;
import com.iaf.console.drift.scorecards.application.RemediationService;
import com.iaf.console.drift.scorecards.application.ScorecardQueries;
import com.iaf.console.drift.scorecards.application.ScorecardSnapshots;
import com.iaf.console.drift.scorecards.domain.Views;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * The scorecards API of {@code contracts/openapi/console-drift-service.yaml} (WP 9.6): the packs,
 * an account's scorecard, the heat map of several accounts (console-internal callers only), a
 * rule's failing resources, one resource's results, and the one-click fix, which relays the BFF's
 * {@code X-On-Behalf-Of} so the change names the person who asked, and which a person may ask for only
 * with {@code scorecards:remediate} and the fix's own action on the resource (403 otherwise). Nothing
 * here fixes anything on its own.
 */
@RestController
public final class ScorecardController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REQUEST_ID = "X-Console-Request-Id";
    static final String ON_BEHALF_OF = "X-On-Behalf-Of";
    static final String REPLAY_HEADER = "Idempotent-Replay";

    private final ScorecardQueries queries;
    private final ScorecardSnapshots snapshots;
    private final RemediationService remediation;

    public ScorecardController(ScorecardQueries queries, ScorecardSnapshots snapshots, RemediationService remediation) {
        this.queries = queries;
        this.snapshots = snapshots;
        this.remediation = remediation;
    }

    /** A list as the contract answers it. */
    public record Items<T>(List<T> items) {
    }

    /** A page as the contract answers it: {@code items}, and {@code next}, null on the last page. */
    public record Listing<T>(List<T> items, String next) {
    }

    @GetMapping("/v1/scorecards/packs")
    public Items<Views.RulePackSummary> packs(ServerWebExchange exchange) {
        principal(exchange);
        return new Items<>(queries.packs());
    }

    @GetMapping("/v1/scorecards/account")
    public Mono<Views.Scorecard> account(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) Integer days) {
        int d = days == null ? 30 : days;
        if (d < 1 || d > ScorecardQueries.MAX_TREND_DAYS) {
            throw new IllegalArgumentException("days is 1 to " + ScorecardQueries.MAX_TREND_DAYS);
        }
        return queries.scorecard(scope(exchange, accountId), d);
    }

    @GetMapping("/v1/scorecards/heatmap")
    public Mono<Views.Heatmap> heatmap(ServerWebExchange exchange, @RequestParam(required = false) String accountIds) {
        internal(exchange);
        if (accountIds == null || accountIds.isBlank()) {
            throw new IllegalArgumentException("accountIds is required: 1 to " + ScorecardQueries.MAX_HEATMAP_ACCOUNTS + " accounts, comma-separated");
        }
        return queries.heatmap(Arrays.stream(accountIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList());
    }

    @GetMapping("/v1/scorecards/packs/{pack}/rules/{ruleId}/failing")
    public Mono<Listing<Views.Evaluation>> failing(ServerWebExchange exchange, @PathVariable String pack, @PathVariable String ruleId,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        AccountScope scope = scope(exchange, accountId);
        return queries.failing(scope, pack, ruleId, type == null || type.isBlank() ? null : type.trim(), CursorPaging.pageSize(limit),
                        after == null || after.isBlank() ? null : CursorPaging.decode(after))
                .map(page -> new Listing<>(page.items(), page.next()));
    }

    @GetMapping("/v1/scorecards/resources")
    public Mono<Views.ResourceCompliance> resource(ServerWebExchange exchange, @RequestParam String wfrn,
            @RequestParam(required = false) String accountId) {
        Wfrn.parse(wfrn);
        return queries.resource(scope(exchange, accountId), wfrn);
    }

    @PostMapping("/v1/scorecards/packs/{pack}/rules/{ruleId}:remediate")
    public Mono<ResponseEntity<ObjectNode>> remediate(ServerWebExchange exchange, @PathVariable String pack, @PathVariable String ruleId,
            @RequestParam(required = false) String accountId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestHeader(value = REQUEST_ID, required = false) String requestId,
            @RequestHeader(value = ON_BEHALF_OF, required = false) String onBehalfOf,
            @RequestBody(required = false) RemediationService.Request body) {
        ConsolePrincipal principal = principal(exchange);
        if (body == null || body.wfrn() == null || body.wfrn().isBlank()) {
            throw new IllegalArgumentException("wfrn is required");
        }
        Wfrn.parse(body.wfrn());
        AccountScope scope = scope(exchange, accountId != null || !principal.internal() ? accountId : Wfrn.parse(body.wfrn()).account());
        return remediation.remediate(scope, pack, ruleId, body, idempotencyKey, requestId, onBehalfOf, principal)
                .map(ScorecardController::respond);
    }

    /** Scores every watched resource again now, of one account or of all. */
    @PostMapping("/internal/scorecards:rescore")
    public Mono<Map<String, Integer>> rescore(ServerWebExchange exchange, @RequestParam(required = false) String accountId) {
        internal(exchange);
        if (accountId != null && !AccountScope.isAccountId(accountId)) {
            throw new IllegalArgumentException("accountId is 12 digits");
        }
        return snapshots.rescore(accountId).map(n -> Map.of("resources", n[0], "changed", n[1]));
    }

    /** Recounts every account and records today's snapshot now. */
    @PostMapping("/internal/scorecards:snapshot")
    public Mono<Map<String, Integer>> snapshot(ServerWebExchange exchange) {
        internal(exchange);
        return snapshots.snapshotAll().map(n -> Map.of("accounts", n));
    }

    static ConsolePrincipal principal(ServerWebExchange exchange) {
        ConsolePrincipal principal = exchange.getAttribute(ConsolePrincipal.ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("no principal on the exchange: the authentication filter did not run");
        }
        return principal;
    }

    /** The caller's own account, or the named one for the console's own services; another account's name is a 404. */
    static AccountScope scope(ServerWebExchange exchange, String accountId) {
        if (accountId != null && !AccountScope.isAccountId(accountId)) {
            throw new IllegalArgumentException("accountId is 12 digits");
        }
        ConsolePrincipal principal = principal(exchange);
        // a scorecard is of the whole account; its stores never filter on the region
        return principal.scope(accountId, principal.region() != null ? null : "global");
    }

    /** Only the console's own services; a person hears 404, as if the route were not there. */
    static ConsolePrincipal internal(ServerWebExchange exchange) {
        ConsolePrincipal principal = principal(exchange);
        if (!principal.internal()) {
            throw new NotFoundException("not found");
        }
        return principal;
    }

    static ResponseEntity<ObjectNode> respond(IdempotentCommands.Outcome outcome) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(outcome.status());
        if (outcome.replay()) {
            builder.header(REPLAY_HEADER, "true");
        }
        return builder.body(outcome.body());
    }
}

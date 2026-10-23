package com.iaf.console.bff.drift.adapters.inbound;

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

import com.iaf.console.bff.drift.application.Rightsizing;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.bff.drift.domain.Recommendation;
import com.iaf.console.bff.drift.domain.RecommendationsSummary;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * Rightsizing (WP 9.22): {@code /console/v1/recommendations}, the idle and oversized resources,
 * their summary, one recommendation, and applying (a resize, or a confirmed delete after a fresh
 * sign-in) or dismissing it, all through the drift service.
 */
@RestController
public class RecommendationsController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Rightsizing rightsizing;

    public RecommendationsController(Rightsizing rightsizing) {
        this.rightsizing = rightsizing;
    }

    /** An apply's body: why, and {@code confirm: true}, which a delete needs. */
    public record ApplyBody(String reason, Boolean confirm) {
    }

    /** A dismissal's body: why, and until when (for good when left out). */
    public record DismissBody(String reason, String until) {
    }

    @GetMapping(path = "/console/v1/recommendations", produces = "application/json")
    public Mono<Page<Recommendation>> list(@RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "wfrn", required = false) String wfrn,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> rightsizing.list(session, type, kind, status, wfrn, limit, after));
    }

    @GetMapping(path = "/console/v1/recommendations/summary", produces = "application/json")
    public Mono<RecommendationsSummary> summary(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(rightsizing::summary);
    }

    @GetMapping(path = "/console/v1/recommendations/{recommendationId}", produces = "application/json")
    public Mono<Recommendation> get(@PathVariable("recommendationId") String recommendationId, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> rightsizing.get(session, recommendationId));
    }

    @PostMapping(path = "/console/v1/recommendations/{recommendationId}:apply", produces = "application/json")
    public Mono<ResponseEntity<DriftResults.ApplyStarted>> apply(@PathVariable("recommendationId") String recommendationId,
            @RequestBody(required = false) ApplyBody body, @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> rightsizing.apply(session, recommendationId, body == null ? null : body.reason(), body == null ? null : body.confirm(),
                        requestId(exchange), idempotencyKey))
                .map(started -> ResponseEntity.status(HttpStatus.ACCEPTED).body(started));
    }

    @PostMapping(path = "/console/v1/recommendations/{recommendationId}:dismiss", produces = "application/json")
    public Mono<Recommendation> dismiss(@PathVariable("recommendationId") String recommendationId, @RequestBody(required = false) DismissBody body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> rightsizing.dismiss(session, recommendationId, body == null ? null : body.reason(),
                body == null ? null : body.until(), requestId(exchange), idempotencyKey));
    }

    private static String requestId(ServerWebExchange exchange) {
        return OnBehalfOf.requestId(Correlation.of(exchange));
    }
}

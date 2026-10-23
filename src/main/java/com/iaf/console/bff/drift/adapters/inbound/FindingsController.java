package com.iaf.console.bff.drift.adapters.inbound;

import java.util.List;

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

import com.iaf.console.bff.drift.application.Findings;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.bff.drift.domain.Finding;
import com.iaf.console.bff.drift.domain.FindingStats;
import com.iaf.console.bff.drift.domain.FindingsSummary;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * Drift findings (WP 5.2): {@code /console/v1/findings} (the list, its summary and the executive
 * numbers, one finding and its three decisions) and a resource's Drift tab,
 * {@code /console/v1/r/{type}/{wfrn}/drift}, all through the drift service.
 */
@RestController
public class FindingsController {

    private static final String TYPE = "{type:[a-z0-9-]+}";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Findings findings;

    public FindingsController(Findings findings) {
        this.findings = findings;
    }

    /** A decision's body: why, and until when for an ignore. Both may be left out. */
    public record DecisionBody(String reason, String until) {
    }

    /** A resource's findings, open and closed, for its Drift tab. */
    public record FindingList(List<Finding> items) {
    }

    @GetMapping(path = "/console/v1/findings", produces = "application/json")
    public Mono<Page<Finding>> list(@RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "wfrn", required = false) String wfrn,
            @RequestParam(name = "openedAfter", required = false) String openedAfter,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> findings.list(session, status, severity, type, wfrn, openedAfter, limit, after));
    }

    @GetMapping(path = "/console/v1/findings/summary", produces = "application/json")
    public Mono<FindingsSummary> summary(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(findings::summary);
    }

    @GetMapping(path = "/console/v1/findings/stats", produces = "application/json")
    public Mono<FindingStats> stats(@RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "until", required = false) String until, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> findings.stats(session, since, until));
    }

    @GetMapping(path = "/console/v1/findings/{findingId}", produces = "application/json")
    public Mono<Finding> get(@PathVariable("findingId") String findingId, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> findings.get(session, findingId));
    }

    @PostMapping(path = "/console/v1/findings/{findingId}:revert", produces = "application/json")
    public Mono<ResponseEntity<DriftResults.RevertStarted>> revert(@PathVariable("findingId") String findingId,
            @RequestBody(required = false) DecisionBody body, @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> findings.revert(session, findingId, body == null ? null : body.reason(), requestId(exchange), idempotencyKey))
                .map(started -> ResponseEntity.status(HttpStatus.ACCEPTED).body(started));
    }

    @PostMapping(path = "/console/v1/findings/{findingId}:accept", produces = "application/json")
    public Mono<Finding> accept(@PathVariable("findingId") String findingId, @RequestBody(required = false) DecisionBody body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> findings.accept(session, findingId, body == null ? null : body.reason(), requestId(exchange), idempotencyKey));
    }

    @PostMapping(path = "/console/v1/findings/{findingId}:ignore", produces = "application/json")
    public Mono<Finding> ignore(@PathVariable("findingId") String findingId, @RequestBody(required = false) DecisionBody body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> findings.ignore(session, findingId, body == null ? null : body.reason(),
                body == null ? null : body.until(), requestId(exchange), idempotencyKey));
    }

    @GetMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}/drift", produces = "application/json")
    public Mono<FindingList> ofResource(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> findings.ofResource(session, type, wfrn)).map(FindingList::new);
    }

    private static String requestId(ServerWebExchange exchange) {
        return OnBehalfOf.requestId(Correlation.of(exchange));
    }
}

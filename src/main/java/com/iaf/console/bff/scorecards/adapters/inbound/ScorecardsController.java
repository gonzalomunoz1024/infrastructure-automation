package com.iaf.console.bff.scorecards.adapters.inbound;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.common.relay.RelayedResponses;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.scorecards.application.Scorecards;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.correlation.Correlation;

import reactor.core.publisher.Mono;

/**
 * Compliance scorecards (WP 9.6, hub PR #19): the account's scorecard, the rule packs, the
 * organization's heat map, a rule's failing resources, a resource's compliance and the one-click
 * fix, through the drift service. The fix goes through the CSRF filter like every other write.
 */
@RestController
public class ScorecardsController {

    private static final String TYPE = "{type:[a-z0-9-]+}";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Scorecards scorecards;

    public ScorecardsController(Scorecards scorecards) {
        this.scorecards = scorecards;
    }

    @GetMapping(path = "/console/v1/scorecards")
    public Mono<ResponseEntity<byte[]>> scorecard(@RequestParam(name = "days", required = false) Integer days, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> scorecards.scorecard(session, days)).map(RelayedResponses::toEntity);
    }

    @GetMapping(path = "/console/v1/scorecards/packs")
    public Mono<ResponseEntity<byte[]>> packs(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(scorecards::packs).map(RelayedResponses::toEntity);
    }

    @GetMapping(path = "/console/v1/scorecards/heatmap")
    public Mono<ResponseEntity<byte[]>> heatmap(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(scorecards::heatmap).map(RelayedResponses::toEntity);
    }

    @GetMapping(path = "/console/v1/scorecards/packs/{pack}/rules/{ruleId}/failing")
    public Mono<ResponseEntity<byte[]>> failing(@PathVariable("pack") String pack, @PathVariable("ruleId") String ruleId,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> scorecards.failing(session, pack, ruleId, type, limit, after))
                .map(RelayedResponses::toEntity);
    }

    @GetMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}/compliance")
    public Mono<ResponseEntity<byte[]>> compliance(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> scorecards.compliance(session, type, wfrn)).map(RelayedResponses::toEntity);
    }

    @PostMapping(path = "/console/v1/scorecards/packs/{pack}/rules/{ruleId}:remediate")
    public Mono<ResponseEntity<byte[]>> remediate(@PathVariable("pack") String pack, @PathVariable("ruleId") String ruleId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        String requestId = OnBehalfOf.requestId(Correlation.of(exchange));
        return CurrentSession.required(exchange).flatMap(session -> RelayedResponses.body(exchange).flatMap(body -> scorecards.remediate(session, pack, ruleId, body, idempotencyKey, requestId)))
                .map(RelayedResponses::toEntity);
    }
}

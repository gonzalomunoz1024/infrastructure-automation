package com.iaf.console.bff.cost.adapters.inbound;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.common.relay.RelayedResponses;
import com.iaf.console.bff.cost.application.Costs;
import com.iaf.console.bff.session.application.CurrentSession;

import reactor.core.publisher.Mono;

/**
 * Cost Explorer ({@code /console/v1/cost}), the price lists, the chargeback (JSON and CSV), a
 * resource's cost and the account's budgets, through the cost service. Writes go through the CSRF
 * filter like every other write.
 */
@RestController
public class CostController {

    private static final String TYPE = "{type:[a-z0-9-]+}";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Costs costs;

    public CostController(Costs costs) {
        this.costs = costs;
    }

    @GetMapping(path = "/console/v1/cost")
    public Mono<ResponseEntity<byte[]>> cost(@RequestParam(name = "scope", required = false) String scope, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> costs.report(session, scope, Costs.params(exchange.getRequest().getQueryParams())))
                .map(RelayedResponses::toEntity);
    }

    @GetMapping(path = "/console/v1/cost/price-lists")
    public Mono<ResponseEntity<byte[]>> priceLists(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(costs::priceLists).map(RelayedResponses::toEntity);
    }

    @GetMapping(path = "/console/v1/cost/chargeback")
    public Mono<ResponseEntity<byte[]>> chargeback(@RequestParam(name = "scope", required = false) String scope, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> costs.chargeback(session, scope, Costs.params(exchange.getRequest().getQueryParams()), false))
                .map(RelayedResponses::toEntity);
    }

    @GetMapping(path = "/console/v1/cost/chargeback.csv")
    public Mono<ResponseEntity<byte[]>> chargebackCsv(@RequestParam(name = "scope", required = false) String scope, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> costs.chargeback(session, scope, Costs.params(exchange.getRequest().getQueryParams()), true))
                .map(RelayedResponses::toEntity);
    }

    @GetMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}/cost")
    public Mono<ResponseEntity<byte[]>> resourceCost(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> costs.resourceCost(session, type, wfrn)).map(RelayedResponses::toEntity);
    }

    @GetMapping(path = "/console/v1/budgets")
    public Mono<ResponseEntity<byte[]>> budgets(@RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> costs.budgets(session, limit, after)).map(RelayedResponses::toEntity);
    }

    @PostMapping(path = "/console/v1/budgets")
    public Mono<ResponseEntity<byte[]>> createBudget(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> RelayedResponses.body(exchange).flatMap(body -> costs.createBudget(session, body, idempotencyKey)))
                .map(RelayedResponses::toEntity);
    }

    @GetMapping(path = "/console/v1/budgets/{budgetId}")
    public Mono<ResponseEntity<byte[]>> budget(@PathVariable("budgetId") String budgetId, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> costs.budget(session, budgetId)).map(RelayedResponses::toEntity);
    }

    @PutMapping(path = "/console/v1/budgets/{budgetId}")
    public Mono<ResponseEntity<byte[]>> updateBudget(@PathVariable("budgetId") String budgetId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> RelayedResponses.body(exchange).flatMap(body -> costs.updateBudget(session, budgetId, ifMatch, body)))
                .map(RelayedResponses::toEntity);
    }

    @DeleteMapping(path = "/console/v1/budgets/{budgetId}")
    public Mono<ResponseEntity<byte[]>> deleteBudget(@PathVariable("budgetId") String budgetId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> costs.deleteBudget(session, budgetId, ifMatch)).map(RelayedResponses::toEntity);
    }

    @GetMapping(path = "/console/v1/budgets/{budgetId}/periods")
    public Mono<ResponseEntity<byte[]>> periods(@PathVariable("budgetId") String budgetId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> costs.periods(session, budgetId, limit, after)).map(RelayedResponses::toEntity);
    }
}

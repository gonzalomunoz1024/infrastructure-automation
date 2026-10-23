package com.iaf.console.drift.monitoring.adapters.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.drift.monitoring.application.MetricsQueries;
import com.iaf.console.drift.monitoring.domain.ResourceMetrics;

import reactor.core.publisher.Mono;

/**
 * {@code GET /v1/metrics} of {@code contracts/openapi/console-drift-service.yaml}: a resource's
 * series through the platform's metrics read, for a resource of the caller's account.
 */
@RestController
public final class MetricsController {

    private final MetricsQueries queries;

    public MetricsController(MetricsQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/v1/metrics")
    public Mono<ResourceMetrics> get(ServerWebExchange exchange,
            @RequestParam String wfrn,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String step,
            @RequestParam(required = false) String accountId) {
        return queries.get(Callers.scopeOf(exchange, accountId, wfrn), wfrn.trim(), from, to, step);
    }
}

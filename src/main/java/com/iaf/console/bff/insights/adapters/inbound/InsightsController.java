package com.iaf.console.bff.insights.adapters.inbound;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.insights.application.ExecutiveHome;
import com.iaf.console.bff.insights.domain.Insights;
import com.iaf.console.bff.insights.domain.InsightsScope;
import com.iaf.console.bff.insights.domain.Period;
import com.iaf.console.bff.session.application.CurrentSession;

import reactor.core.publisher.Mono;

/** {@code GET /console/v1/insights?period=7d|30d|90d&scope=account|organization}: the executive home's numbers. */
@RestController
public class InsightsController {

    private final ExecutiveHome home;

    public InsightsController(ExecutiveHome home) {
        this.home = home;
    }

    @GetMapping(path = "/console/v1/insights", produces = "application/json")
    public Mono<Insights> insights(@RequestParam(name = "period", required = false) String period, @RequestParam(name = "scope", required = false) String scope,
            ServerWebExchange exchange) {
        Period chosenPeriod = Period.parse(period);
        InsightsScope chosenScope = InsightsScope.parse(scope);
        return CurrentSession.required(exchange).flatMap(session -> home.insights(session, chosenPeriod, chosenScope));
    }
}

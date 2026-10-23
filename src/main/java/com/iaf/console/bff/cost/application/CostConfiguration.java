package com.iaf.console.bff.cost.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.cost.ports.outbound.CostPort;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.session.application.Sessions;

/** Wires cost and budgets over the cost service's client (the HTTP adapter registers itself; the demo profile mounts the stand-in it defaults to). */
@Configuration(proxyBeanMethods = false)
public class CostConfiguration {

    /** The path the demo profile mounts its cost service stand-in under. */
    public static final String DEMO_PATH = "/demo-cost";

    @Bean
    public Costs costs(Access access, Sessions sessions, PlatformProperties platform, CostPort cost, ObjectMapper json) {
        return new Costs(access, sessions, platform, cost, json);
    }

    @Bean
    public BudgetGuard budgetGuard(CostPort cost, CostProperties properties) {
        return new BudgetGuard(cost, properties.checkTimeout());
    }
}

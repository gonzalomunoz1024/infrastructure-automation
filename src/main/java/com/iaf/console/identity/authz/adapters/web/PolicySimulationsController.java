package com.iaf.console.identity.authz.adapters.web;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.authz.application.PolicySimulations;
import com.iaf.console.identity.authz.application.Simulation;
import com.iaf.console.identity.authz.application.SimulationRequest;
import com.iaf.console.identity.common.auth.Caller;

import reactor.core.publisher.Mono;

/** {@code POST /v1/accounts/{accountId}/policy-simulations}: a computation, nothing is stored. */
@RestController
public class PolicySimulationsController {

    private final PolicySimulations simulations;

    public PolicySimulationsController(PolicySimulations simulations) {
        this.simulations = simulations;
    }

    @PostMapping("/v1/accounts/{accountId}/policy-simulations")
    public Mono<Simulation> simulate(Caller caller, @PathVariable String accountId, @RequestBody SimulationRequest body) {
        return simulations.simulate(caller, accountId, body);
    }
}

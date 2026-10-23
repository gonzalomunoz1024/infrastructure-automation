package com.iaf.console.identity.authz.application;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.iaf.console.identity.authz.domain.Decision;

/** The contract's {@code PolicySimulation}: one result per check, in order, and the account's authorization version they were decided at. */
public record Simulation(List<Result> results, long version) {

    /** A check and its decision, as {@code POST /v1/authorize} would answer it for the simulated principal. */
    public record Result(String action, String resource, @JsonUnwrapped Decision decision) {
    }
}

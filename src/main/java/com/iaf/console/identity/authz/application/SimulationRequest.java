package com.iaf.console.identity.authz.application;

import java.util.List;

import com.iaf.console.identity.authz.domain.Check;
import com.iaf.console.identity.iam.domain.Statement;

/**
 * The contract's {@code PolicySimulationRequest}: who to simulate (one of a person of the
 * account, a role by id or name, or a set of groups by id or name), unsaved policy documents
 * they would also hold, for a role a draft boundary in place of its own, and the checks.
 */
public record SimulationRequest(Subject principal, List<Draft> policies, Draft boundary, List<Check> checks) {

    /** Exactly one of the three. */
    public record Subject(String userId, String role, List<String> groups) {
    }

    /** An unsaved policy document. */
    public record Draft(String name, List<Statement> statements) {
    }
}

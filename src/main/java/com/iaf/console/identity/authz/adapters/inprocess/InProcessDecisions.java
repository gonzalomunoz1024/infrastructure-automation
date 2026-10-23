package com.iaf.console.identity.authz.adapters.inprocess;

import java.util.ArrayList;
import java.util.List;

import com.iaf.console.identity.authz.application.AuthorizeRequest;
import com.iaf.console.identity.authz.application.AuthorizeResponse;
import com.iaf.console.identity.authz.application.Snapshots;
import com.iaf.console.identity.authz.domain.Check;
import com.iaf.console.identity.authz.domain.CompiledAccount;
import com.iaf.console.identity.authz.domain.Decision;
import com.iaf.console.identity.authz.domain.Evaluator;
import com.iaf.console.identity.authz.ports.outbound.DecisionPort;

import reactor.core.publisher.Mono;

/** Decides in this process with the Java evaluator over the current snapshot: the default, and the demo's "mock OPA". */
public final class InProcessDecisions implements DecisionPort {

    private final Snapshots snapshots;

    public InProcessDecisions(Snapshots snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public Mono<AuthorizeResponse> decide(AuthorizeRequest request) {
        return snapshots.get().map(compiled -> {
            List<Decision> decisions = new ArrayList<>(request.checks().size());
            for (Check check : request.checks()) {
                decisions.add(Evaluator.decide(compiled, request.principal(), check));
            }
            CompiledAccount account = compiled.accounts().get(request.principal().accountId());
            return new AuthorizeResponse(decisions, account == null ? 0 : account.version());
        });
    }
}

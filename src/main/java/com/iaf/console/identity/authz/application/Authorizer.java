package com.iaf.console.identity.authz.application;

import java.util.List;
import com.iaf.console.identity.authz.domain.Evaluator;
import com.iaf.console.identity.authz.domain.CompiledAccount;
import com.iaf.console.identity.authz.domain.Compiled;
import java.util.Map;
import java.util.ArrayList;

import com.iaf.console.identity.authz.domain.Check;
import com.iaf.console.identity.authz.domain.Decision;
import com.iaf.console.identity.authz.domain.Principal;
import com.iaf.console.identity.authz.ports.outbound.DecisionPort;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.common.web.Denial;
import com.iaf.console.identity.common.web.DeniedException;
import com.iaf.console.identity.iam.domain.Actions;
import com.iaf.console.identity.iam.domain.PolicyValidation;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * {@code POST /v1/authorize} and this service's own IAM checks: a caller's write on an account is
 * authorized like any other action, and a refusal is a 403 with the denial block.
 */
public final class Authorizer implements AccessGuard {

    private final DecisionPort decisions;
    private final Snapshots snapshots;

    private static final String PROBE = "probe";

    public Authorizer(DecisionPort decisions, Snapshots snapshots) {
        this.snapshots = snapshots;
        this.decisions = decisions;
    }

    public Mono<AuthorizeResponse> authorize(Caller caller, AuthorizeRequest request) {
        Names.Check check = new Names.Check();
        Principal principal = request.principal();
        check.require(principal != null, "principal", "principal is required");
        if (principal != null) {
            check.require(AccountScope.isAccountId(principal.accountId()), "principal.accountId", "accountId is twelve digits");
            check.require(caller.covers(principal.accountId() == null ? "" : principal.accountId()), "principal.accountId",
                    "a user's call decides for the user's own account");
        }
        List<Check> checks = request.checks();
        check.require(checks != null && !checks.isEmpty() && checks.size() <= AuthorizeRequest.MAX_CHECKS, "checks", "1 to 100 checks");
        if (checks != null) {
            for (int i = 0; i < checks.size(); i++) {
                Check c = checks.get(i);
                check.require(c != null && Actions.isValid(c.action()), "checks[" + i + "].action", "action is <service>:<verb>");
                check.require(c != null && PolicyValidation.isPattern(c.resource()), "checks[" + i + "].resource", "resource is a wfrn or a wfrn pattern");
            }
        }
        check.done("the authorize request");
        return decisions.decide(request);
    }

    @Override
    public Mono<Void> require(Caller caller, String accountId, String action, String resource) {
        if (caller.isInternal()) {
            return Mono.empty();
        }
        AuthorizeRequest request = new AuthorizeRequest(new Principal(caller.id(), accountId, caller.groups(), caller.service()),
                List.of(new Check(action, resource, null)));
        return decisions.decide(request).flatMap(response -> {
            Decision d = response.decisions().isEmpty() ? Decision.deny("no decision") : response.decisions().get(0);
            if (d.allowed()) {
                return Mono.empty();
            }
            return Mono.error(new DeniedException(new Denial(Denial.CONSOLE_IAM, action, resource, d.reason(), d.role(), d.policyId(), d.statement(),
                    d.requestable() == null ? Boolean.FALSE : d.requestable(), d.suggestedRole())));
        });
    }

    @Override
    public Mono<String> suggestedRole(Caller caller, String accountId, String action, String resource) {
        AuthorizeRequest request = new AuthorizeRequest(new Principal(caller.id(), accountId, caller.groups(), caller.service()),
                List.of(new Check(action, resource, null)));
        return decisions.decide(request).flatMap(response -> {
            Decision d = response.decisions().isEmpty() ? null : response.decisions().get(0);
            return d == null || d.allowed() || d.suggestedRole() == null ? Mono.empty() : Mono.just(d.suggestedRole());
        });
    }

    @Override
    public Mono<List<String>> groupsAllowed(String accountId, String action, String resource) {
        return snapshots.get().map(compiled -> {
            CompiledAccount account = compiled.accounts().get(accountId);
            if (account == null) {
                return List.of();
            }
            Check check = new Check(action, resource, null);
            List<String> allowed = new ArrayList<>();
            for (String group : account.groups().keySet()) {
                CompiledAccount probed = account.withUser(PROBE, new CompiledAccount.CompiledUser("active", List.of(group)));
                Compiled world = new Compiled(Map.of(accountId, probed), compiled.revision());
                if (Evaluator.decide(world, new Principal(PROBE, accountId, null, null), check).allowed()) {
                    allowed.add(group);
                }
            }
            return List.copyOf(allowed);
        });
    }
}

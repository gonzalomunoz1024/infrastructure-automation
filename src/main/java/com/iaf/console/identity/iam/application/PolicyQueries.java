package com.iaf.console.identity.iam.application;

import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.identity.iam.ports.outbound.PolicyStore;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** The reads on policies. */
public final class PolicyQueries {

    private static final String KIND = "policy";

    private final PolicyStore policies;
    private final AccessGuard guard;
    private final IamSupport support;

    public PolicyQueries(PolicyStore policies, AccessGuard guard, IamSupport support) {
        this.policies = policies;
        this.guard = guard;
        this.support = support;
    }

    public Mono<Policy> get(Caller caller, String accountId, String policyId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:get", support.resource(accountId, KIND, policyId)).thenReturn(scope))
                .flatMap(scope -> policies.find(scope, policyId).switchIfEmpty(Mono.error(NotFoundException.of(KIND, policyId))));
    }

    public Mono<Page<Policy>> list(Caller caller, String accountId, int limit, String after) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:list", support.anyResource(accountId, KIND)).thenReturn(scope))
                .flatMap(scope -> policies.list(scope, limit, after));
    }
}

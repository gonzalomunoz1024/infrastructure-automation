package com.iaf.console.identity.iam.application;

import java.time.Clock;

import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.Versions;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.identity.iam.domain.PolicyValidation;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.ports.outbound.ActionCatalogPort;
import com.iaf.console.identity.iam.ports.outbound.PolicyStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/** The writes on policies: every statement is checked before anything is written. */
public final class PolicyCommands {

    private static final String KIND = "policy";

    private final PolicyStore policies;
    private final RoleStore roles;
    private final ActionCatalogPort catalog;
    private final AccessGuard guard;
    private final IdentityEvents events;
    private final IamSupport support;
    private final Ids ids;
    private final Clock clock;

    public PolicyCommands(PolicyStore policies, RoleStore roles, ActionCatalogPort catalog, AccessGuard guard, IdentityEvents events, IamSupport support,
            Ids ids, Clock clock) {
        this.policies = policies;
        this.roles = roles;
        this.catalog = catalog;
        this.guard = guard;
        this.events = events;
        this.support = support;
        this.ids = ids;
        this.clock = clock;
    }

    public Mono<Policy> create(Caller caller, String accountId, PolicyInput input) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:create", support.anyResource(accountId, KIND)).thenReturn(scope))
                .flatMap(scope -> catalog.getCatalog().flatMap(actions -> {
                    PolicyValidation.check(input.name(), input.statements(), actions).done("the policy");
                    Policy policy = Policy.create(ids.policy(), accountId, input.name(), input.description(), input.statements(), false, clock.instant());
                    return policies.insert(policy)
                            .flatMap(stored -> events.changed(IdentityEvents.Subject.POLICY, stored.policyId(), accountId, caller.actor(), stored.version(),
                                    "created", stored).thenReturn(stored));
                }));
    }

    public Mono<Policy> replace(Caller caller, String accountId, String policyId, PolicyInput input, Long ifMatch) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:update", support.resource(accountId, KIND, policyId)).thenReturn(scope))
                .flatMap(scope -> policies.find(scope, policyId)
                        .switchIfEmpty(Mono.error(NotFoundException.of(KIND, policyId)))
                        .flatMap(current -> {
                            Versions.check(ifMatch, current.version(), "the policy");
                            if (current.builtIn()) {
                                return Mono.error(new ConflictException("a managed policy cannot be changed"));
                            }
                            return catalog.getCatalog().flatMap(actions -> {
                                PolicyValidation.check(input.name(), input.statements(), actions).done("the policy");
                                Policy changed = current.replaced(input.name(), input.description(), input.statements(), clock.instant());
                                return policies.update(changed, current.version())
                                        .switchIfEmpty(Mono.error(new ConflictException("the policy moved on; read it again")))
                                        .flatMap(stored -> events.changed(IdentityEvents.Subject.POLICY, stored.policyId(), accountId, caller.actor(),
                                                stored.version(), "updated", stored).thenReturn(stored));
                            });
                        }));
    }

    public Mono<Void> delete(Caller caller, String accountId, String policyId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:delete", support.resource(accountId, KIND, policyId)).thenReturn(scope))
                .flatMap(scope -> policies.find(scope, policyId)
                        .switchIfEmpty(Mono.error(NotFoundException.of(KIND, policyId)))
                        .flatMap(current -> {
                            if (current.builtIn()) {
                                return Mono.error(new ConflictException("a managed policy cannot be deleted"));
                            }
                            return roles.findAll(scope).filter(r -> r.policies().contains(policyId) || policyId.equals(r.boundary())).map(Role::name).collectList()
                                    .flatMap(holders -> holders.isEmpty() ? Mono.empty()
                                            : Mono.error(new ConflictException("the policy is held by " + String.join(", ", holders), holders)))
                                    .then(policies.delete(scope, policyId))
                                    .then(events.changed(IdentityEvents.Subject.POLICY, policyId, accountId, caller.actor(), current.version() + 1, "deleted",
                                            null));
                        }));
    }
}

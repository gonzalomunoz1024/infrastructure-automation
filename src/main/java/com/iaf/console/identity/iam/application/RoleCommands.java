package com.iaf.console.identity.iam.application;

import java.time.Clock;
import java.util.List;

import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.Versions;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.domain.ManagedIam;
import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.PolicyStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/** The writes on roles. */
public final class RoleCommands {

    private static final String KIND = "role";

    private final RoleStore roles;
    private final PolicyStore policies;
    private final GroupStore groups;
    private final AccessGuard guard;
    private final IdentityEvents events;
    private final IamSupport support;
    private final Ids ids;
    private final Clock clock;

    public RoleCommands(RoleStore roles, PolicyStore policies, GroupStore groups, AccessGuard guard, IdentityEvents events, IamSupport support, Ids ids,
            Clock clock) {
        this.roles = roles;
        this.policies = policies;
        this.groups = groups;
        this.guard = guard;
        this.events = events;
        this.support = support;
        this.ids = ids;
        this.clock = clock;
    }

    public Mono<Role> create(Caller caller, String accountId, RoleInput input) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:create", support.anyResource(accountId, KIND)).thenReturn(scope))
                .flatMap(scope -> {
                    new Names.Check().require(Names.isName(input.name()), "name", "name matches ^[a-z0-9-]{2,64}$").done("the role");
                    return policies.findAll(scope).collectList().flatMap(all -> {
                        List<String> policyIds = IamSupport.resolve(input.policies() == null ? List.of() : input.policies(), all, Policy::policyId,
                                Policy::name, "policies", "policy");
                        Role role = Role.create(ids.role(), accountId, input.name(), input.description(), policyIds, false, clock.instant());
                        return roles.insert(role)
                                .flatMap(stored -> events.changed(IdentityEvents.Subject.ROLE, stored.roleId(), accountId, caller.actor(), stored.version(),
                                        "created", stored).thenReturn(stored));
                    });
                });
    }

    public Mono<Role> update(Caller caller, String accountId, String roleId, RoleInput input, Long ifMatch) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:update", support.resource(accountId, KIND, roleId)).thenReturn(scope))
                .flatMap(scope -> roles.find(scope, roleId)
                        .switchIfEmpty(Mono.error(NotFoundException.of(KIND, roleId)))
                        .flatMap(current -> {
                            Versions.check(ifMatch, current.version(), "the role");
                            new Names.Check().require(input.name() == null || Names.isName(input.name()), "name", "name matches ^[a-z0-9-]{2,64}$")
                                    .done("the role");
                            if (current.builtIn() && ((input.name() != null && !input.name().equals(current.name())) || input.policies() != null)) {
                                return Mono.error(new ConflictException("a managed role keeps its name and policies"));
                            }
                            return policies.findAll(scope).collectList().flatMap(all -> {
                                List<String> policyIds = IamSupport.resolve(input.policies(), all, Policy::policyId, Policy::name, "policies", "policy");
                                Role changed = current.changed(input.name(), input.description(), policyIds, clock.instant());
                                return roles.update(changed, current.version())
                                        .switchIfEmpty(Mono.error(new ConflictException("the role moved on; read it again")))
                                        .flatMap(stored -> events.changed(IdentityEvents.Subject.ROLE, stored.roleId(), accountId, caller.actor(),
                                                stored.version(), "updated", stored).thenReturn(stored));
                            });
                        }));
    }

    public Mono<Void> delete(Caller caller, String accountId, String roleId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:delete", support.resource(accountId, KIND, roleId)).thenReturn(scope))
                .flatMap(scope -> roles.find(scope, roleId)
                        .switchIfEmpty(Mono.error(NotFoundException.of(KIND, roleId)))
                        .flatMap(current -> {
                            if (ManagedIam.ACCOUNT_ADMIN.equals(current.name())) {
                                return Mono.error(new ConflictException("the account-admin role cannot be deleted"));
                            }
                            return groups.findAll(scope).filter(g -> g.roles().contains(roleId)).map(g -> g.name()).collectList()
                                    .flatMap(holders -> holders.isEmpty() ? Mono.empty()
                                            : Mono.error(new ConflictException("the role is held by " + String.join(", ", holders), holders)))
                                    .then(roles.delete(scope, roleId))
                                    .then(events.changed(IdentityEvents.Subject.ROLE, roleId, accountId, caller.actor(), current.version() + 1, "deleted", null));
                        }));
    }
}

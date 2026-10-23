package com.iaf.console.identity.iam.application;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.ports.outbound.AccountProvisioningPort;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.domain.ManagedIam;
import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.PolicyStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What a new account is born with: the managed policies, roles and groups, and its first owner
 * (found by email, or created to be linked at first sign-in) in the owners group.
 */
public final class IamProvisioning implements AccountProvisioningPort {

    private final PolicyStore policies;
    private final RoleStore roles;
    private final GroupStore groups;
    private final UserStore users;
    private final MembershipStore memberships;
    private final IdentityEvents events;
    private final IamSupport support;
    private final Ids ids;
    private final Clock clock;

    public IamProvisioning(PolicyStore policies, RoleStore roles, GroupStore groups, UserStore users, MembershipStore memberships, IdentityEvents events,
            IamSupport support, Ids ids, Clock clock) {
        this.policies = policies;
        this.roles = roles;
        this.groups = groups;
        this.users = users;
        this.memberships = memberships;
        this.events = events;
        this.support = support;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public Mono<Void> provision(Account account, String ownerEmail, String actor) {
        String accountId = account.accountId();
        AccountScope scope = AccountScope.of(accountId, account.defaultRegion() == null ? "global" : account.defaultRegion(), actor);
        Map<String, String> roleIdsByName = new HashMap<>();
        Map<String, String> groupIdsByName = new HashMap<>();
        return Flux.fromIterable(ManagedIam.roles(support.partition(), accountId))
                .concatMap(managed -> {
                    Policy policy = Policy.create(ids.policy(), accountId, managed.policyName(), managed.policyDescription(), managed.statements(), true,
                            clock.instant());
                    return policies.insert(policy)
                            .flatMap(p -> events.changed(IdentityEvents.Subject.POLICY, p.policyId(), accountId, actor, p.version(), "created", p).thenReturn(p))
                            .flatMap(p -> {
                                Role role = Role.create(ids.role(), accountId, managed.name(), managed.description(), List.of(p.policyId()), true,
                                        clock.instant());
                                return roles.insert(role);
                            })
                            .flatMap(r -> events.changed(IdentityEvents.Subject.ROLE, r.roleId(), accountId, actor, r.version(), "created", r).thenReturn(r))
                            .doOnNext(r -> roleIdsByName.put(r.name(), r.roleId()));
                })
                .then(Flux.fromIterable(ManagedIam.groups())
                        .concatMap(managed -> {
                            List<String> idpGroups = managed.owningTeamJoins() ? List.of(account.owningTeam()) : List.of();
                            Group group = Group.create(ids.group(), accountId, managed.name(), managed.description(),
                                    List.of(roleIdsByName.get(managed.role())), idpGroups, true, clock.instant());
                            return groups.insert(group)
                                    .flatMap(g -> events.changed(IdentityEvents.Subject.GROUP, g.groupId(), accountId, actor, g.version(), "created", g)
                                            .thenReturn(g))
                                    .doOnNext(g -> groupIdsByName.put(g.name(), g.groupId()));
                        })
                        .then())
                .then(Mono.defer(() -> ownerEmail == null ? Mono.empty() : owner(scope, ownerEmail, groupIdsByName.get(ManagedIam.OWNERS), actor)));
    }

    private Mono<Void> owner(AccountScope scope, String email, String ownersGroupId, String actor) {
        return users.findByEmail(email)
                .switchIfEmpty(Mono.defer(() -> users.insert(User.create(ids.user(), email, null, null, null, clock.instant()))))
                .flatMap(user -> memberships.insert(Membership.create(scope.accountId(), user, List.of(ownersGroupId), clock.instant())))
                .flatMap(m -> events.changed(IdentityEvents.Subject.USER, m.userId(), scope.accountId(), actor, m.version(), "created",
                        Memberships.snapshotOf(m)));
    }
}

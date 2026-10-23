package com.iaf.console.identity.iam.application;

import java.time.Clock;
import java.util.List;

import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.Versions;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.domain.GroupView;
import com.iaf.console.identity.iam.domain.ManagedIam;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** The writes on groups. */
public final class GroupCommands {

    private static final String KIND = "group";

    private final GroupStore groups;
    private final RoleStore roles;
    private final MembershipStore memberships;
    private final Memberships membership;
    private final AccessGuard guard;
    private final IdentityEvents events;
    private final IamSupport support;
    private final Ids ids;
    private final Clock clock;

    public GroupCommands(GroupStore groups, RoleStore roles, MembershipStore memberships, Memberships membership, AccessGuard guard, IdentityEvents events,
            IamSupport support, Ids ids, Clock clock) {
        this.groups = groups;
        this.roles = roles;
        this.memberships = memberships;
        this.membership = membership;
        this.guard = guard;
        this.events = events;
        this.support = support;
        this.ids = ids;
        this.clock = clock;
    }

    public Mono<GroupView> create(Caller caller, String accountId, GroupInput input) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:create", support.anyResource(accountId, KIND)).thenReturn(scope))
                .flatMap(scope -> {
                    new Names.Check().require(Names.isName(input.name()), "name", "name matches ^[a-z0-9-]{2,64}$")
                            .require(input.idpGroups() == null || input.idpGroups().stream().allMatch(g -> g != null && !g.isBlank()), "idpGroups",
                                    "an identity provider group is a non-empty name")
                            .done("the group");
                    return roles.findAll(scope).collectList().flatMap(all -> {
                        List<String> roleIds = IamSupport.resolve(input.roles() == null ? List.of() : input.roles(), all, Role::roleId, Role::name, "roles",
                                "role");
                        Group group = Group.create(ids.group(), accountId, input.name(), input.description(), roleIds, input.idpGroups(), false,
                                clock.instant());
                        return groups.insert(group)
                                .flatMap(stored -> membership.setMembers(scope, stored, input.members() == null ? List.of() : input.members(), caller.actor())
                                        .then(events.changed(IdentityEvents.Subject.GROUP, stored.groupId(), accountId, caller.actor(), stored.version(),
                                                "created", stored))
                                        .then(view(scope, stored)));
                    });
                });
    }

    public Mono<GroupView> update(Caller caller, String accountId, String groupId, GroupInput input, Long ifMatch) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:update", support.resource(accountId, KIND, groupId)).thenReturn(scope))
                .flatMap(scope -> groups.find(scope, groupId)
                        .switchIfEmpty(Mono.error(NotFoundException.of(KIND, groupId)))
                        .flatMap(current -> {
                            Versions.check(ifMatch, current.version(), "the group");
                            new Names.Check().require(input.name() == null || Names.isName(input.name()), "name", "name matches ^[a-z0-9-]{2,64}$")
                                    .done("the group");
                            if (current.builtIn() && input.name() != null && !input.name().equals(current.name())) {
                                return Mono.error(new ConflictException("a built-in group keeps its name"));
                            }
                            return roles.findAll(scope).collectList().flatMap(all -> {
                                List<String> roleIds = IamSupport.resolve(input.roles(), all, Role::roleId, Role::name, "roles", "role");
                                if (ManagedIam.OWNERS.equals(current.name()) && roleIds != null) {
                                    boolean keepsAdmin = all.stream().anyMatch(r -> ManagedIam.ACCOUNT_ADMIN.equals(r.name()) && roleIds.contains(r.roleId()));
                                    if (!keepsAdmin) {
                                        return Mono.error(new ConflictException("the owners group keeps the account-admin role"));
                                    }
                                }
                                Group changed = current.changed(input.name(), input.description(), roleIds, input.idpGroups(), clock.instant());
                                return groups.update(changed, current.version())
                                        .switchIfEmpty(Mono.error(new ConflictException("the group moved on; read it again")))
                                        .flatMap(stored -> (input.members() == null ? Mono.<Void>empty()
                                                : membership.setMembers(scope, stored, input.members(), caller.actor()))
                                                .then(events.changed(IdentityEvents.Subject.GROUP, stored.groupId(), accountId, caller.actor(), stored.version(),
                                                        "updated", stored))
                                                .then(view(scope, stored)));
                            });
                        }));
    }

    public Mono<Void> delete(Caller caller, String accountId, String groupId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:delete", support.resource(accountId, KIND, groupId)).thenReturn(scope))
                .flatMap(scope -> groups.find(scope, groupId)
                        .switchIfEmpty(Mono.error(NotFoundException.of(KIND, groupId)))
                        .flatMap(current -> {
                            if (ManagedIam.OWNERS.equals(current.name())) {
                                return Mono.error(new ConflictException("the owners group cannot be deleted"));
                            }
                            return membership.setMembers(scope, current, List.of(), caller.actor())
                                    .then(groups.delete(scope, groupId))
                                    .then(events.changed(IdentityEvents.Subject.GROUP, groupId, accountId, caller.actor(), current.version() + 1, "deleted",
                                            null));
                        }));
    }

    Mono<GroupView> view(AccountScope scope, Group group) {
        return memberships.findByGroup(scope, group.groupId()).map(Membership::userId).collectList().map(members -> GroupView.of(group, members));
    }
}

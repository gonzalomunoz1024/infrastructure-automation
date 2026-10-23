package com.iaf.console.identity.accessrequests.application;

import java.time.Clock;
import java.util.List;

import com.iaf.console.identity.accessrequests.domain.AccessRequest;
import com.iaf.console.identity.accessrequests.ports.outbound.AccessRequestStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.common.web.Denial;
import com.iaf.console.identity.common.web.DeniedException;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.application.Memberships;
import com.iaf.console.identity.iam.domain.Actions;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.domain.ManagedIam;
import com.iaf.console.identity.iam.domain.PolicyValidation;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.application.PrincipalQueries;
import com.iaf.console.identity.principals.domain.UserStatus;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The writes on access requests: a member opens one for a role (one pending per user and role);
 * an admin ({@code access-request:decide}) approves, which puts the requester in the group that
 * carries the role, or rejects. Nobody decides their own request, so a requester who may decide
 * others' is left out of its approvers and refused on {@code :decide}.
 */
public final class AccessRequestCommands {

    /** The kind of an access request in an iam wfrn. */
    public static final String KIND = "access-request";
    static final int APPROVERS_SHOWN = 100;

    private final AccessRequestStore requests;
    private final MembershipStore memberships;
    private final GroupStore groups;
    private final RoleStore roles;
    private final Memberships membership;
    private final PrincipalQueries principals;
    private final AccessGuard guard;
    private final IamSupport support;
    private final IdentityEvents events;
    private final Ids ids;
    private final Clock clock;

    public AccessRequestCommands(AccessRequestStore requests, MembershipStore memberships, GroupStore groups, RoleStore roles, Memberships membership,
            PrincipalQueries principals, AccessGuard guard, IamSupport support, IdentityEvents events, Ids ids, Clock clock) {
        this.events = events;
        this.requests = requests;
        this.memberships = memberships;
        this.groups = groups;
        this.roles = roles;
        this.membership = membership;
        this.principals = principals;
        this.guard = guard;
        this.support = support;
        this.ids = ids;
        this.clock = clock;
    }

    public Mono<AccessRequest> open(Caller caller, String accountId, OpenAccessRequest input) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "access-request:create", support.anyResource(accountId, KIND)).thenReturn(scope))
                .flatMap(scope -> {
                    if (!caller.isUser()) {
                        return Mono.error(new InvalidInputException("an access request is a user's", List.of(Problem.error("invalid", "requester",
                                "only a user opens an access request"))));
                    }
                    String named = input.role() == null ? null : ManagedIam.canonicalRole(input.role());
                    new Names.Check()
                            .require(input.action() != null && Actions.isValid(input.action()), "action", "action is <service>:<verb>")
                            .require(input.resource() == null || PolicyValidation.isPattern(input.resource()), "resource", "resource is a wfrn or a wfrn pattern")
                            .require(input.reason() != null && !input.reason().isBlank() && input.reason().length() <= 2000, "reason",
                                    "reason is 1 to 2000 characters")
                            .require(named == null || Names.isName(named), "role", "role is the name of a role of the account")
                            .done("the access request");
                    String resource = input.resource() == null ? support.everything(accountId) : input.resource();
                    // the role asked for: the one named, else the managed role the denial suggested
                    Mono<String> role = named != null ? Mono.just(named)
                            : guard.suggestedRole(caller, accountId, input.action(), resource)
                                    .switchIfEmpty(Mono.error(new InvalidInputException("the access request is not valid",
                                            List.of(Problem.error("required", "role", "no managed role would allow " + input.action() + " on " + resource
                                                    + " (or it is allowed already); name the role to ask for")))));
                    return role.flatMap(roleName -> memberships.find(scope, caller.id())
                            .switchIfEmpty(Mono.error(NotFoundException.of("user", caller.id())))
                            .flatMap(me -> roles.findByName(scope, roleName)
                                    .switchIfEmpty(Mono.error(new InvalidInputException("the access request is not valid",
                                            List.of(Problem.error("unknown", "role", "'" + roleName + "' is no role of this account")))))
                                    .then(requests.findPending(scope, caller.id(), roleName)
                                            .flatMap(pending -> Mono.<AccessRequest>error(new AccessRequestPending(pending.served(clock.instant())))))
                                    .switchIfEmpty(Mono.defer(() -> approvers(scope, me.userId())
                                            .flatMap(approvers -> requests.insert(AccessRequest.open(ids.accessRequest(), accountId,
                                                    new AccessRequest.Requester(me.userId(), me.email(), me.name()), input.action(), resource, roleName,
                                                    input.reason(), approvers, clock.instant())))
                                            .flatMap(r -> events.changed(IdentityEvents.Subject.ACCESS_REQUEST, r.requestId(), accountId, caller.actor(),
                                                    r.version(), "created", r).thenReturn(r))))));
                });
    }

    /**
     * Whom the request goes to: the active members of the groups whose policies allow deciding,
     * except the requester, at most {@value #APPROVERS_SHOWN}.
     */
    private Mono<List<String>> approvers(AccountScope scope, String requesterId) {
        return guard.groupsAllowed(scope.accountId(), "access-request:decide", support.anyResource(scope.accountId(), KIND))
                .flatMap(names -> groups.findAll(scope).filter(g -> names.contains(g.name())).map(Group::groupId).collectList())
                .flatMapMany(groupIds -> memberships.findAll(scope)
                        .filter(m -> m.status() == UserStatus.active && m.groupIds().stream().anyMatch(groupIds::contains)))
                .map(Membership::userId)
                .filter(userId -> !userId.equals(requesterId))
                .distinct()
                .take(APPROVERS_SHOWN)
                .collectList();
    }

    public Mono<AccessRequest> decide(Caller caller, String accountId, String requestId, DecideAccessRequest input) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "access-request:decide", support.resource(accountId, KIND, requestId)).thenReturn(scope))
                .flatMap(scope -> {
                    boolean approve = "approve".equals(input.decision());
                    new Names.Check().require(approve || "reject".equals(input.decision()), "decision", "decision is approve or reject")
                            .require(input.comment() == null || input.comment().length() <= 2000, "comment", "comment is at most 2000 characters")
                            .done("the decision");
                    return requests.find(scope, requestId)
                            .switchIfEmpty(Mono.error(NotFoundException.of("access request", requestId)))
                            .flatMap(request -> {
                                if (caller.isUser() && caller.id().equals(request.requester().userId())) {
                                    return Mono.error(new DeniedException(new Denial(Denial.CONSOLE_IAM, "access-request:decide",
                                            support.resource(accountId, KIND, requestId), "nobody decides their own access request", null, null, null,
                                            Boolean.FALSE, null)));
                                }
                                if (request.status(clock.instant()) != AccessRequest.Status.pending) {
                                    return Mono.error(new ConflictException("the request is " + request.status(clock.instant())));
                                }
                                Mono<Void> grant = approve ? grant(scope, request, caller.actor()) : Mono.empty();
                                AccessRequest decided = request.decided(approve ? AccessRequest.Status.approved : AccessRequest.Status.rejected, caller.actor(),
                                        input.comment(), clock.instant());
                                return grant.then(requests.update(decided, request.version()))
                                        .switchIfEmpty(Mono.error(new ConflictException("the request moved on; read it again")))
                                        .flatMap(stored -> events.changed(IdentityEvents.Subject.ACCESS_REQUEST, stored.requestId(), accountId, caller.actor(),
                                                stored.version(), stored.status().name(), stored).thenReturn(stored));
                            });
                });
    }

    /**
     * Puts the requester in the group that carries the role (the managed group of a managed role,
     * else the first group holding it), with the GroupChanged event that goes with a new member.
     */
    private Mono<Void> grant(AccountScope scope, AccessRequest request, String actor) {
        return roles.findByName(scope, request.role())
                .switchIfEmpty(Mono.error(new ConflictException("the role " + request.role() + " no longer exists")))
                .flatMap(role -> {
                    String managedGroup = ManagedIam.groupFor(request.role());
                    Mono<Group> group = managedGroup == null ? Mono.empty() : groups.findByName(scope, managedGroup);
                    return group.switchIfEmpty(groups.findAll(scope).filter(g -> g.roles().contains(role.roleId())).next())
                            .switchIfEmpty(Mono.error(new ConflictException("no group of the account holds the role " + request.role())));
                })
                .flatMap(group -> memberships.find(scope, request.requester().userId())
                        .switchIfEmpty(Mono.error(new ConflictException("the requester is no longer a user of the account")))
                        .flatMap(m -> membership.join(scope, m, group.groupId(), actor))
                        // the group's version moves with its members, so the GroupChanged carries a new key
                        .then(groups.update(group.changed(group.name(), group.description(), group.roles(), group.idpGroups(), clock.instant()), group.version()))
                        .switchIfEmpty(Mono.error(new ConflictException("the group " + group.name() + " moved on; decide again")))
                        .flatMap(stored -> events.changed(IdentityEvents.Subject.GROUP, stored.groupId(), scope.accountId(), actor, stored.version(), "updated",
                                stored)))
                .then();
    }
}

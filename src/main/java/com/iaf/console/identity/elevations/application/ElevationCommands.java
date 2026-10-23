package com.iaf.console.identity.elevations.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.iaf.console.identity.accounts.ports.outbound.AccountClosurePort;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.common.web.Denial;
import com.iaf.console.identity.common.web.DeniedException;
import com.iaf.console.identity.elevations.domain.Elevation;
import com.iaf.console.identity.elevations.domain.ElevationRules;
import com.iaf.console.identity.elevations.ports.outbound.ElevationStore;
import com.iaf.console.identity.events.IdentityChange;
import com.iaf.console.identity.events.IdentityChangeListener;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.domain.ManagedIam;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.identity.principals.domain.UserStatus;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The writes on elevations. A person asks for a role of the account with a reason
 * ({@code elevation:create}): the first rule that covers it approves it at once, otherwise it waits
 * for whoever may decide it ({@code elevation:decide}), never the person. The person may always
 * end their own; anyone else needs {@code elevation:revoke}. Break-glass access is the internal
 * principal's, for any console user with a named approver who is not that user. Removing a
 * person from the account ends their elevations there, and closing the account ends all of them.
 */
public final class ElevationCommands implements AccountClosurePort, IdentityChangeListener {

    /** The kind of an elevation in an iam wfrn. */
    public static final String KIND = "elevation";
    static final int APPROVERS_SHOWN = 100;
    static final int MAX_TEXT = 2000;
    static final int MAX_TICKET = 200;
    static final String OWN = "nobody decides their own elevation";
    static final String SYSTEM = Caller.internal("console-identity-service", "global").actor();

    private final ElevationStore elevations;
    private final MembershipStore memberships;
    private final UserStore users;
    private final GroupStore groups;
    private final RoleStore roles;
    private final AccountStore accounts;
    private final AccessGuard guard;
    private final IamSupport support;
    private final Supplier<IdentityEvents> events;
    private final ElevationRules rules;
    private final Ids ids;
    private final Clock clock;

    public ElevationCommands(ElevationStore elevations, MembershipStore memberships, UserStore users, GroupStore groups, RoleStore roles,
            AccountStore accounts, AccessGuard guard, IamSupport support, Supplier<IdentityEvents> events, ElevationRules rules, Ids ids, Clock clock) {
        this.elevations = elevations;
        this.memberships = memberships;
        this.users = users;
        this.groups = groups;
        this.roles = roles;
        this.accounts = accounts;
        this.guard = guard;
        this.support = support;
        this.events = events;
        this.rules = rules;
        this.ids = ids;
        this.clock = clock;
    }

    public Mono<Elevation> request(Caller caller, String accountId, RequestElevation input) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "elevation:create", support.anyResource(accountId, KIND)).thenReturn(scope))
                .flatMap(scope -> {
                    if (!caller.isUser()) {
                        return Mono.error(new InvalidInputException("an elevation is a person's", List.of(Problem.error("invalid", "requester",
                                "only a person asks for an elevation"))));
                    }
                    String role = input == null ? null : canonical(input.role());
                    String reason = input == null ? null : input.reason();
                    int minutes = input == null || input.durationMinutes() == null ? rules.defaultMinutes() : input.durationMinutes();
                    new Names.Check()
                            .require(role != null && Names.isName(role), "role", "role is the name of a role of the account")
                            .require(text(reason), "reason", "reason is 1 to 2000 characters")
                            .require(minutes >= ElevationRules.MIN_MINUTES && minutes <= rules.maxMinutes(), "durationMinutes",
                                    "durationMinutes is " + ElevationRules.MIN_MINUTES + " to " + rules.maxMinutes())
                            .done("the elevation");
                    return memberships.find(scope, caller.id())
                            .switchIfEmpty(Mono.error(() -> NotFoundException.of("user", caller.id())))
                            .flatMap(me -> Mono.zip(roles.findAll(scope).collectList(), groups.findAll(scope).collectList()).flatMap(t -> {
                                if (t.getT1().stream().noneMatch(r -> r.name().equals(role))) {
                                    return Mono.error(new InvalidInputException("the elevation is not valid",
                                            List.of(Problem.error("unknown", "role", "'" + role + "' is no role of this account"))));
                                }
                                Set<String> held = heldRoles(me, t.getT1(), t.getT2());
                                if (held.contains(role)) {
                                    return Mono.error(new InvalidInputException("the elevation is not valid",
                                            List.of(Problem.error("invalid", "role", "you hold " + role + " through a group already"))));
                                }
                                Instant now = clock.instant();
                                return open(scope, me.userId(), role, Elevation.Kind.ELEVATION, now)
                                        .flatMap(existing -> Mono.<Elevation>error(new ElevationOpen(existing)))
                                        .switchIfEmpty(Mono.defer(() -> {
                                            Elevation.Person person = new Elevation.Person(me.userId(), me.email(), me.name());
                                            Optional<ElevationRules.AutoApproval> rule = rules.approving(role, held, minutes);
                                            Mono<Elevation> made = rule.isPresent()
                                                    ? Mono.just(Elevation.approvedAtOnce(ids.elevation(), accountId, person, role, reason, minutes,
                                                            "rule:" + rule.get().name(), now))
                                                    : approvers(scope, me.userId()).map(approvers -> Elevation.ask(ids.elevation(), accountId, person, role,
                                                            reason, minutes, approvers, now));
                                            return made.flatMap(elevations::insert).flatMap(e -> publish(e, caller.actor(), "created"));
                                        }));
                            }));
                });
    }

    public Mono<Elevation> decide(Caller caller, String accountId, String elevationId, DecideElevation input) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "elevation:decide", support.resource(accountId, KIND, elevationId)).thenReturn(scope))
                .flatMap(scope -> {
                    String decision = input == null ? null : input.decision();
                    String comment = input == null ? null : input.comment();
                    boolean approve = "approve".equals(decision);
                    new Names.Check().require(approve || "reject".equals(decision), "decision", "decision is approve or reject")
                            .require(comment == null || comment.length() <= MAX_TEXT, "comment", "comment is at most 2000 characters")
                            .done("the decision");
                    return elevations.find(scope, elevationId)
                            .switchIfEmpty(Mono.error(() -> NotFoundException.of("elevation", elevationId)))
                            .flatMap(e -> {
                                if (caller.isUser() && caller.id().equals(e.user().userId())) {
                                    return Mono.error(new DeniedException(new Denial(Denial.CONSOLE_IAM, "elevation:decide",
                                            support.resource(accountId, KIND, elevationId), OWN, null, null, null, Boolean.FALSE, null)));
                                }
                                Instant now = clock.instant();
                                Elevation.Status status = e.status(now, rules.pendingFor());
                                if (status != Elevation.Status.pending) {
                                    return Mono.error(new ConflictException("the elevation is " + status));
                                }
                                Mono<Void> stillPossible = approve ? roles.findByName(scope, e.role())
                                        .switchIfEmpty(Mono.error(() -> new ConflictException("the role " + e.role() + " no longer exists")))
                                        .then(memberships.find(scope, e.user().userId())
                                                .switchIfEmpty(Mono.error(() -> new ConflictException("the person is no longer a user of the account"))))
                                        .then() : Mono.empty();
                                Elevation decided = approve ? e.approved(caller.actor(), comment, now) : e.rejected(caller.actor(), comment, now);
                                return stillPossible.then(elevations.update(decided, e.version()))
                                        .switchIfEmpty(Mono.error(() -> new ConflictException("the elevation moved on; read it again")))
                                        .flatMap(stored -> publish(stored, caller.actor(), approve ? "approved" : "rejected"));
                            });
                });
    }

    public Mono<Elevation> revoke(Caller caller, String accountId, String elevationId, RevokeElevation input) {
        String comment = input == null ? null : input.comment();
        return support.scope(caller, accountId)
                .flatMap(scope -> {
                    new Names.Check().require(comment == null || comment.length() <= MAX_TEXT, "comment", "comment is at most 2000 characters")
                            .done("the revocation");
                    return elevations.find(scope, elevationId)
                            .switchIfEmpty(Mono.error(() -> NotFoundException.of("elevation", elevationId)))
                            .flatMap(e -> {
                                boolean own = caller.isUser() && caller.id().equals(e.user().userId());
                                Mono<Void> allowed = own ? Mono.empty()
                                        : guard.require(caller, accountId, "elevation:revoke", support.resource(accountId, KIND, elevationId));
                                return allowed.then(Mono.defer(() -> end(e, caller.actor(), comment)
                                        .switchIfEmpty(Mono.error(() -> new ConflictException("the elevation moved on; read it again")))));
                            });
                });
    }

    public Mono<Elevation> breakGlass(Caller caller, String accountId, OpenBreakGlass input) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException("no such operation"));
        }
        String userId = input == null ? null : blankToNull(input.userId());
        String role = input == null || input.role() == null ? ManagedIam.ACCOUNT_ADMIN : canonical(input.role());
        String reason = input == null ? null : input.reason();
        String approvedBy = input == null ? null : blankToNull(input.approvedBy());
        String ticket = input == null ? null : blankToNull(input.ticket());
        int minutes = input == null || input.durationMinutes() == null ? Math.min(60, rules.breakGlassMaxMinutes()) : input.durationMinutes();
        new Names.Check()
                .require(userId != null, "userId", "userId is required")
                .require(role != null && Names.isName(role), "role", "role is the name of a role of the account")
                .require(text(reason), "reason", "reason is 1 to 2000 characters")
                .require(minutes >= ElevationRules.MIN_MINUTES && minutes <= rules.breakGlassMaxMinutes(), "durationMinutes",
                        "durationMinutes is " + ElevationRules.MIN_MINUTES + " to " + rules.breakGlassMaxMinutes())
                .require(approvedBy != null, "approvedBy", "approvedBy is required")
                .require(approvedBy == null || !approvedBy.equals(userId), "approvedBy", "nobody approves their own break-glass access")
                .require(ticket == null || ticket.length() <= MAX_TICKET, "ticket", "ticket is at most 200 characters")
                .done("the break-glass access");
        AccountScope scope = caller.scope(accountId);
        return accounts.find(scope)
                .switchIfEmpty(Mono.error(() -> NotFoundException.of("account", accountId)))
                .flatMap(account -> account.isActive() ? Mono.just(account) : Mono.error(new ConflictException("the account is " + account.status())))
                .then(users.findById(userId).switchIfEmpty(Mono.error(() -> NotFoundException.of("user", userId))))
                .flatMap(user -> user.status() == UserStatus.suspended ? Mono.error(new ConflictException("the user is suspended")) : Mono.just(user))
                .flatMap(user -> users.findById(approvedBy)
                        .switchIfEmpty(Mono.error(() -> new InvalidInputException("the break-glass access is not valid",
                                List.of(Problem.error("unknown", "approvedBy", "'" + approvedBy + "' is no console user")))))
                        .then(roles.findByName(scope, role)
                                .switchIfEmpty(Mono.error(() -> new InvalidInputException("the break-glass access is not valid",
                                        List.of(Problem.error("unknown", "role", "'" + role + "' is no role of this account"))))))
                        .then(Mono.defer(() -> {
                            Instant now = clock.instant();
                            return open(scope, user.userId(), role, Elevation.Kind.BREAK_GLASS, now)
                                    .flatMap(existing -> Mono.<Elevation>error(new ElevationOpen(existing)))
                                    .switchIfEmpty(Mono.defer(() -> elevations.insert(Elevation.breakGlass(ids.elevation(), accountId, person(user), role, reason,
                                                    ticket, minutes, caller.actor(), approvedBy, now))
                                            .flatMap(e -> publish(e, caller.actor(), "created"))));
                        })));
    }

    /** Closing the account ends every elevation of it that is still open. */
    @Override
    public Mono<Void> accountClosed(AccountScope scope, String actor) {
        return elevations.findAllOpen(scope).collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(e -> end(e, actor, "the account closed").onErrorResume(ConflictException.class, x -> Mono.empty()))
                .then();
    }

    /** A person removed from the account loses their elevations there; break-glass access, which needs no membership, stays. */
    @Override
    public Mono<Void> changed(IdentityChange change) {
        if (!"UserChanged".equals(change.type()) || !"deleted".equals(change.action())) {
            return Mono.empty();
        }
        AccountScope scope = AccountScope.of(change.accountId(), "global", SYSTEM);
        return elevations.findOpen(scope, change.aggregateId(), null, Elevation.Kind.ELEVATION).collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(e -> end(e, SYSTEM, "the person left the account").onErrorResume(ConflictException.class, x -> Mono.empty()))
                .then();
    }

    /** Ends one that is open now: 409 when it ended already; empty when it moved on meanwhile. */
    private Mono<Elevation> end(Elevation e, String actor, String comment) {
        Instant now = clock.instant();
        Elevation.Status status = e.status(now, rules.pendingFor());
        if (status != Elevation.Status.pending && status != Elevation.Status.active) {
            return Mono.error(new ConflictException("the elevation is " + status));
        }
        Elevation ended = e.ended(actor, comment, now);
        return elevations.update(ended, e.version())
                .flatMap(stored -> publish(stored, actor, stored.status() == Elevation.Status.cancelled ? "cancelled" : "revoked"));
    }

    /** The open one of a person, role and kind at {@code now}, when there is one. */
    private Mono<Elevation> open(AccountScope scope, String userId, String role, Elevation.Kind kind, Instant now) {
        return elevations.findOpen(scope, userId, role, kind).filter(e -> e.isOpen(now, rules.pendingFor())).next()
                .map(e -> e.served(now, rules.pendingFor()));
    }

    /** Who may decide an ask: the active members of the groups allowed {@code elevation:decide}, except the person, at most {@value #APPROVERS_SHOWN}. */
    private Mono<List<String>> approvers(AccountScope scope, String requesterId) {
        return guard.groupsAllowed(scope.accountId(), "elevation:decide", support.anyResource(scope.accountId(), KIND))
                .flatMap(names -> groups.findAll(scope).filter(g -> names.contains(g.name())).map(Group::groupId).collectList())
                .flatMapMany(groupIds -> memberships.findAll(scope)
                        .filter(m -> m.status() == UserStatus.active && m.groupIds().stream().anyMatch(groupIds::contains)))
                .map(Membership::userId)
                .filter(userId -> !userId.equals(requesterId))
                .distinct()
                .take(APPROVERS_SHOWN)
                .collectList();
    }

    private Mono<Elevation> publish(Elevation e, String actor, String action) {
        return events.get().changed(IdentityEvents.Subject.ELEVATION, e.elevationId(), e.accountId(), actor, e.version(), action, e).thenReturn(e);
    }

    /** The role names a member holds through their groups. */
    static Set<String> heldRoles(Membership member, List<Role> roles, List<Group> groups) {
        Map<String, String> roleNames = new HashMap<>();
        roles.forEach(r -> roleNames.put(r.roleId(), r.name()));
        Map<String, Group> byId = new HashMap<>();
        groups.forEach(g -> byId.put(g.groupId(), g));
        Set<String> held = new LinkedHashSet<>();
        for (String groupId : member.groupIds()) {
            Group g = byId.get(groupId);
            if (g != null) {
                g.roles().stream().map(roleNames::get).filter(java.util.Objects::nonNull).forEach(held::add);
            }
        }
        return held;
    }

    private static Elevation.Person person(User user) {
        return new Elevation.Person(user.userId(), user.email(), user.name());
    }

    private static String canonical(String role) {
        return role == null ? null : ManagedIam.canonicalRole(role.strip());
    }

    private static boolean text(String s) {
        return s != null && !s.isBlank() && s.length() <= MAX_TEXT;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

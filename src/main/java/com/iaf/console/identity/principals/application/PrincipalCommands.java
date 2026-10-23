package com.iaf.console.identity.principals.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.iaf.console.identity.accounts.domain.AccountStatus;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.Versions;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.application.Memberships;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.principals.domain.AccountUser;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.domain.Resolution;
import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.identity.principals.domain.UserStatus;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The writes on users and memberships, and the BFF's resolve at sign-in. */
public final class PrincipalCommands {

    private static final String KIND = "user";

    private final UserStore users;
    private final MembershipStore memberships;
    private final GroupStore groups;
    private final AccountStore accounts;
    private final Memberships membership;
    private final PrincipalQueries queries;
    private final AccessGuard guard;
    private final IdentityEvents events;
    private final IamSupport support;
    private final Ids ids;
    private final Clock clock;

    public PrincipalCommands(UserStore users, MembershipStore memberships, GroupStore groups, AccountStore accounts, Memberships membership,
            PrincipalQueries queries, AccessGuard guard, IdentityEvents events, IamSupport support, Ids ids, Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.groups = groups;
        this.accounts = accounts;
        this.membership = membership;
        this.queries = queries;
        this.guard = guard;
        this.events = events;
        this.support = support;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * The BFF's call after the OIDC callback (internal principal only). Finds the person by the
     * provider's issuer and subject, else by email (linking the identity on first sign-in), else
     * creates them when their identity provider groups map to an account. Every mapped account
     * gets a membership, or the mapped groups added to the one it has. 404 when the person ends
     * with no account.
     */
    public Mono<Resolution> resolve(Caller caller, ResolveRequest request) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException("no such operation"));
        }
        new Names.Check()
                .require(request.issuer() != null && !request.issuer().isBlank(), "issuer", "issuer is required")
                .require(request.subject() != null && !request.subject().isBlank(), "subject", "subject is required")
                .require(request.email() == null || Names.isEmail(request.email()), "email", "email must be an email address")
                .done("the principal");
        String email = Names.normalizeEmail(request.email());
        List<String> idpGroups = request.groups() == null ? List.of() : request.groups();
        Mono<User> known = users.findByIssuerAndSubject(request.issuer(), request.subject())
                .switchIfEmpty(Mono.defer(() -> email == null ? Mono.empty() : users.findByEmail(email).flatMap(byEmail -> {
                    if (byEmail.issuer() != null && !(byEmail.issuer().equals(request.issuer()) && byEmail.subject().equals(request.subject()))) {
                        return Mono.error(new ConflictException("the email " + email + " is linked to another identity"));
                    }
                    return Mono.just(byEmail);
                })));
        Mono<Map<String, List<Group>>> mapped = idpGroups.isEmpty() ? Mono.just(Map.of())
                : groups.findByIdpGroups(idpGroups).collectList().map(found -> {
                    Map<String, List<Group>> byAccount = new LinkedHashMap<>();
                    for (Group g : found) {
                        byAccount.computeIfAbsent(g.accountId(), k -> new ArrayList<>()).add(g);
                    }
                    return byAccount;
                });
        return Mono.zip(known.map(List::of).defaultIfEmpty(List.of()), mapped).flatMap(tuple -> {
            List<User> found = tuple.getT1();
            Map<String, List<Group>> byAccount = tuple.getT2();
            Mono<User> user;
            if (!found.isEmpty()) {
                User u = found.get(0).signedIn(request.issuer(), request.subject(), request.name(), clock.instant());
                user = users.save(u).flatMap(saved -> saved.name() != null && !saved.name().equals(found.get(0).name())
                        ? memberships.updateUserFields(saved.userId(), saved.email(), saved.name()).thenReturn(saved) : Mono.just(saved));
            } else if (byAccount.isEmpty() || email == null) {
                return Mono.error(new NotFoundException("no account for this user"));
            } else {
                user = users.insert(User.create(ids.user(), email, request.name(), request.issuer(), request.subject(), clock.instant()).signedIn(
                        request.issuer(), request.subject(), request.name(), clock.instant()));
            }
            return user.flatMap(u -> Flux.fromIterable(byAccount.entrySet())
                    .concatMap(e -> joinMapped(u, e.getKey(), e.getValue(), caller.actor()))
                    .then(queries.resolution(u)));
        });
    }

    private Mono<Membership> joinMapped(User user, String accountId, List<Group> mappedGroups, String actor) {
        AccountScope scope = AccountScope.of(accountId, "global", actor);
        List<String> wanted = mappedGroups.stream().map(Group::groupId).toList();
        return accounts.findById(accountId)
                .filter(a -> a.status() == AccountStatus.active)
                .flatMap(account -> memberships.find(scope, user.userId())
                        .flatMap(current -> {
                            Set<String> ids = new LinkedHashSet<>(current.groupIds());
                            ids.addAll(wanted);
                            return membership.setGroups(scope, current, List.copyOf(ids), actor);
                        })
                        .switchIfEmpty(Mono.defer(() -> memberships.insert(Membership.create(accountId, user, wanted, clock.instant()))
                                .flatMap(m -> events.changed(IdentityEvents.Subject.USER, m.userId(), accountId, actor, m.version(), "created",
                                        Memberships.snapshotOf(m)).thenReturn(m)))));
    }

    public Mono<AccountUser> addUser(Caller caller, String accountId, AddUser input) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:create", support.anyResource(accountId, KIND)).thenReturn(scope))
                .flatMap(scope -> {
                    new Names.Check().require(Names.isEmail(input.email()), "email", "email must be an email address").done("the user");
                    String email = Names.normalizeEmail(input.email());
                    return accounts.find(scope)
                            .switchIfEmpty(Mono.error(NotFoundException.of("account", accountId)))
                            .flatMap(account -> account.status() == AccountStatus.active ? Mono.just(account)
                                    : Mono.error(new ConflictException("the account is " + account.status())))
                            .then(groups.findAll(scope).collectList())
                            .flatMap(all -> {
                                List<String> groupIds = IamSupport.resolve(input.groups() == null ? List.of() : input.groups(), all, Group::groupId, Group::name,
                                        "groups", "group");
                                return users.findByEmail(email)
                                        .switchIfEmpty(Mono.defer(() -> users.insert(User.create(ids.user(), email, input.name(), null, null, clock.instant()))))
                                        .flatMap(user -> memberships.find(scope, user.userId())
                                                .flatMap(existing -> Mono.<Membership>error(new ConflictException(email + " is already a user of this account")))
                                                .switchIfEmpty(Mono.defer(() -> memberships.insert(Membership.create(accountId, user, groupIds, clock.instant()))))
                                                .flatMap(m -> events.changed(IdentityEvents.Subject.USER, m.userId(), accountId, caller.actor(), m.version(),
                                                        "created", Memberships.snapshotOf(m)).thenReturn(m))
                                                .map(m -> AccountUser.of(user, m, IamSupport.names(m.groupIds(), namesOf(all)))));
                            });
                });
    }

    public Mono<AccountUser> updateUser(Caller caller, String accountId, String userId, UpdateUser input, Long ifMatch) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:update", support.resource(accountId, KIND, userId)).thenReturn(scope))
                .flatMap(scope -> memberships.find(scope, userId)
                        .switchIfEmpty(Mono.error(NotFoundException.of(KIND, userId)))
                        .flatMap(current -> {
                            Versions.check(ifMatch, current.version(), "the user");
                            return groups.findAll(scope).collectList().flatMap(all -> {
                                List<String> groupIds = IamSupport.resolve(input.groups(), all, Group::groupId, Group::name, "groups", "group");
                                Membership changed = current.changed(input.name(), input.status(), groupIds, clock.instant());
                                String action = input.status() == UserStatus.suspended && current.status() != UserStatus.suspended ? "suspended" : "updated";
                                Mono<Void> rename = input.name() == null ? Mono.empty()
                                        : users.findById(userId).flatMap(u -> users.save(u.renamed(input.name(), clock.instant())))
                                                .then(memberships.updateUserFields(userId, current.email(), input.name()));
                                return membership.guardOwners(scope, current, changed)
                                        .then(rename)
                                        .then(memberships.update(changed, current.version()))
                                        .switchIfEmpty(Mono.error(new ConflictException("the user moved on; read it again")))
                                        .flatMap(stored -> events.changed(IdentityEvents.Subject.USER, userId, accountId, caller.actor(), stored.version(), action,
                                                Memberships.snapshotOf(stored)).thenReturn(stored))
                                        .flatMap(stored -> users.findById(userId).map(u -> AccountUser.of(u, stored, IamSupport.names(stored.groupIds(), namesOf(all)))));
                            });
                        }));
    }

    public Mono<Void> removeUser(Caller caller, String accountId, String userId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:delete", support.resource(accountId, KIND, userId)).thenReturn(scope))
                .flatMap(scope -> memberships.find(scope, userId)
                        .switchIfEmpty(Mono.error(NotFoundException.of(KIND, userId)))
                        .flatMap(current -> membership.guardOwners(scope, current, null)
                                .then(memberships.delete(scope, userId))
                                .then(events.changed(IdentityEvents.Subject.USER, userId, accountId, caller.actor(), current.version() + 1, "deleted", null))));
    }

    static Map<String, String> namesOf(List<Group> all) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Group g : all) {
            names.put(g.groupId(), g.name());
        }
        return names;
    }
}

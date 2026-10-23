package com.iaf.console.identity.principals.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.identity.accounts.domain.Account;
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

    private static final Logger log = LoggerFactory.getLogger(PrincipalCommands.class);
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
     * creates them when their identity provider groups map to an account. When the request carries
     * the person's {@code groups}, they are the identity provider's word on every active account:
     * a mapped account without a membership gets one, and every membership the person has is synced
     * ({@link Memberships#sync}), so groups held through a provider group they left go, even in an
     * account nothing maps them to any more. Without {@code groups} nothing is synced. 404 when the
     * person ends with no account.
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
            return user.flatMap(u -> (request.groups() == null ? Mono.<Void>empty() : syncAll(u, byAccount, caller.actor())).then(queries.resolution(u)));
        });
    }

    /** Syncs the person's memberships in every account their groups map to or they are in. */
    private Mono<Void> syncAll(User user, Map<String, List<Group>> byAccount, String actor) {
        return memberships.findByUser(user.userId()).collectMap(Membership::accountId).flatMap(held -> {
            Set<String> accountIds = new LinkedHashSet<>(byAccount.keySet());
            accountIds.addAll(held.keySet());
            return Flux.fromIterable(accountIds)
                    .concatMap(accountId -> syncIn(user, accountId, held.get(accountId), byAccount.getOrDefault(accountId, List.of()), actor))
                    .then();
        });
    }

    /** One account, when it is active: a membership for a newly mapped person, else the sync of the one they have. */
    private Mono<Void> syncIn(User user, String accountId, Membership current, List<Group> mappedGroups, String actor) {
        AccountScope scope = AccountScope.of(accountId, "global", actor);
        List<String> mappedNow = mappedGroups.stream().map(Group::groupId).toList();
        return accounts.findById(accountId).filter(Account::isActive).flatMap(account -> {
            if (current != null) {
                return sync(scope, current, mappedNow, actor);
            }
            if (mappedNow.isEmpty()) {
                return Mono.empty();
            }
            return memberships.insert(Membership.mapped(accountId, user, mappedNow, clock.instant()))
                    .flatMap(m -> events.changed(IdentityEvents.Subject.USER, m.userId(), accountId, actor, m.version(), "created", Memberships.snapshotOf(m)))
                    // a sign-in of the same person at the same time made it first: sync that one
                    .onErrorResume(ConflictException.class, e -> memberships.find(scope, user.userId()).flatMap(m -> sync(scope, m, mappedNow, actor)));
        });
    }

    private Mono<Void> sync(AccountScope scope, Membership current, List<String> mappedNow, String actor) {
        return membership.sync(scope, current, mappedNow, actor)
                .doOnNext(synced -> synced.kept().forEach(groupId -> log.warn(
                        "kept group {} of {} in account {}: they are its last active owner, though their identity provider groups no longer map to it",
                        groupId, current.userId(), scope.accountId())))
                .then();
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
                                                .map(m -> AccountUser.of(user, m, namesOf(all))));
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
                                Set<String> mapped = all.stream().filter(g -> !g.idpGroups().isEmpty()).map(Group::groupId).collect(Collectors.toSet());
                                Membership changed = current.changed(input.name(), input.status(), groupIds, mapped, clock.instant());
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
                                        .flatMap(stored -> users.findById(userId).map(u -> AccountUser.of(u, stored, namesOf(all))));
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

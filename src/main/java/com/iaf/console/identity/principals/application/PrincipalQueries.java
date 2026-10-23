package com.iaf.console.identity.principals.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.iaf.console.identity.accounts.domain.AccountStatus;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.accounts.ports.outbound.MembershipLookupPort;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.elevations.domain.Elevation;
import com.iaf.console.identity.elevations.ports.outbound.ElevationStore;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.domain.ManagedIam;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.domain.AccountMembership;
import com.iaf.console.identity.principals.domain.AccountUser;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.domain.Resolution;
import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The reads on users: one, a page, and a resolved user's accounts. Also the accounts slice's membership lookup. */
public final class PrincipalQueries implements MembershipLookupPort {

    private static final String KIND = "user";

    private final UserStore users;
    private final MembershipStore memberships;
    private final GroupStore groups;
    private final RoleStore roles;
    private final AccountStore accounts;
    private final AccessGuard guard;
    private final IamSupport support;
    private final ElevationStore elevations;
    private final Clock clock;

    public PrincipalQueries(UserStore users, MembershipStore memberships, GroupStore groups, RoleStore roles, AccountStore accounts, AccessGuard guard,
            IamSupport support, ElevationStore elevations, Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.groups = groups;
        this.roles = roles;
        this.accounts = accounts;
        this.guard = guard;
        this.support = support;
        this.elevations = elevations;
        this.clock = clock;
    }

    public Mono<AccountUser> get(Caller caller, String accountId, String userId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:get", support.resource(accountId, KIND, userId)).thenReturn(scope))
                .flatMap(scope -> memberships.find(scope, userId)
                        .switchIfEmpty(Mono.error(NotFoundException.of(KIND, userId)))
                        .flatMap(m -> Mono.zip(users.findById(userId), groupNames(scope))
                                .map(t -> AccountUser.of(t.getT1(), m, t.getT2()))));
    }

    public Mono<Page<AccountUser>> list(Caller caller, String accountId, String query, int limit, String after) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:list", support.anyResource(accountId, KIND)).thenReturn(scope))
                .flatMap(scope -> memberships.list(scope, query, limit, after).flatMap(page -> {
                    List<String> ids = page.items().stream().map(Membership::userId).toList();
                    return Mono.zip(users.findByIds(ids).collectMap(User::userId), groupNames(scope)).map(t -> {
                        List<AccountUser> items = page.items().stream()
                                .filter(m -> t.getT1().containsKey(m.userId()))
                                .map(m -> AccountUser.of(t.getT1().get(m.userId()), m, t.getT2()))
                                .toList();
                        return new Page<>(items, page.next());
                    });
                }));
    }

    /**
     * The resolve answer: the user and every account they may use (closed accounts left out), with
     * their group names and managed role names there, those held for a while included and listed
     * in {@code elevations}. Break-glass access adds an account the user is no member of, with no
     * groups; an elevation counts in an account the user is a member of only, as it decides.
     */
    public Mono<Resolution> resolution(User user) {
        Instant now = clock.instant();
        return elevations.findActiveByUser(user.userId()).filter(e -> e.holds(now)).collectList().flatMap(held -> {
            Set<String> memberOf = new HashSet<>();
            Flux<AccountMembership> asMember = memberships.findByUser(user.userId())
                    .doOnNext(m -> memberOf.add(m.accountId()))
                    .concatMap(m -> accounts.findById(m.accountId())
                            .filter(a -> a.status() != AccountStatus.closed)
                            .flatMap(a -> membership(a, m.groupIds(), user, held.stream().filter(e -> e.accountId().equals(a.accountId())).toList())));
            Flux<AccountMembership> byBreakGlass = Flux.defer(() -> Flux.fromIterable(held.stream()
                            .filter(e -> e.kind() == Elevation.Kind.BREAK_GLASS && !memberOf.contains(e.accountId()))
                            .map(Elevation::accountId).distinct().toList()))
                    .concatMap(accountId -> accounts.findById(accountId)
                            .filter(a -> a.status() != AccountStatus.closed)
                            .flatMap(a -> membership(a, List.of(), user, held.stream()
                                    .filter(e -> e.accountId().equals(accountId) && e.kind() == Elevation.Kind.BREAK_GLASS).toList())));
            return asMember.concatWith(byBreakGlass).collectList();
        }).flatMap(list -> list.isEmpty() ? Mono.error(new NotFoundException("no account for this user")) : Mono.just(new Resolution(user, list)));
    }

    private Mono<AccountMembership> membership(com.iaf.console.identity.accounts.domain.Account a, List<String> groupIds, User user, List<Elevation> held) {
        AccountScope scope = AccountScope.of(a.accountId(), a.defaultRegion() == null ? "global" : a.defaultRegion(), user.userId());
        return Mono.zip(groups.findAll(scope).collectList(), roles.findAll(scope).collectList()).map(t -> {
            Map<String, Group> byId = new LinkedHashMap<>();
            t.getT1().forEach(g -> byId.put(g.groupId(), g));
            Map<String, String> roleNames = new LinkedHashMap<>();
            t.getT2().forEach(r -> roleNames.put(r.roleId(), r.name()));
            List<String> groupNames = groupIds.stream().filter(byId::containsKey).map(id -> byId.get(id).name()).toList();
            Set<String> roleSet = new LinkedHashSet<>();
            for (String gid : groupIds) {
                Group g = byId.get(gid);
                if (g != null) {
                    g.roles().stream().filter(roleNames::containsKey).map(roleNames::get).filter(ManagedIam::isManagedRole).forEach(roleSet::add);
                }
            }
            held.stream().map(Elevation::role).filter(ManagedIam::isManagedRole).forEach(roleSet::add);
            List<AccountMembership.Held> elevated = held.stream()
                    .map(e -> new AccountMembership.Held(e.elevationId(), e.role(), e.kind().wire(), e.until())).toList();
            return new AccountMembership(a.accountId(), a.name(), groupNames, List.copyOf(roleSet), a.defaultRegion(), elevated);
        });
    }

    @Override
    public Mono<List<String>> listAccountIds(String userId) {
        return memberships.findByUser(userId).map(Membership::accountId).collectList();
    }

    private Mono<Map<String, String>> groupNames(AccountScope scope) {
        return groups.findAll(scope).collectMap(Group::groupId, Group::name).map(LinkedHashMap::new);
    }

    /** The user ids holding a role in an account, for an access request's approvers. */
    public Flux<String> usersWithRole(AccountScope scope, String roleName) {
        return roles.findAll(scope).filter(r -> r.name().equals(roleName)).map(Role::roleId).collectList()
                .flatMapMany(roleIds -> groups.findAll(scope).filter(g -> g.roles().stream().anyMatch(roleIds::contains)).map(Group::groupId).collectList()
                        .flatMapMany(groupIds -> memberships.findAll(scope).filter(m -> m.groupIds().stream().anyMatch(groupIds::contains))
                                .map(Membership::userId).distinct()));
    }
}

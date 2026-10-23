package com.iaf.console.identity.iam.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.domain.ManagedIam;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.domain.UserStatus;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The group side of memberships: putting users in and out of a group, with the one guardrail
 * every path shares: the owners group keeps at least one active member. An admin's or a review's
 * change goes through {@link #setGroups} (the admin rule of {@link Membership#changed}), the
 * identity provider's through {@link #sync} (the rule of {@link Membership#synced}), which never
 * takes the owners group from the account's last active owner: it keeps the group and says so.
 */
public final class Memberships {

    private final MembershipStore memberships;
    private final GroupStore groups;
    private final IdentityEvents events;
    private final Clock clock;

    public Memberships(MembershipStore memberships, GroupStore groups, IdentityEvents events, Clock clock) {
        this.memberships = memberships;
        this.groups = groups;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Sets a membership's groups by the admin rule: a mapped group taken away is excluded from the
     * identity provider's syncs, a group added is the admin's. Empty when the membership moved on.
     * Emits {@code UserChanged}.
     */
    public Mono<Membership> setGroups(AccountScope scope, Membership current, List<String> groupIds, String actor) {
        if (current.groupIds().equals(groupIds)) {
            return Mono.just(current);
        }
        return mappedGroupIds(scope).flatMap(mapped -> {
            Membership changed = current.regrouped(groupIds, mapped, clock.instant());
            return guardOwners(scope, current, changed)
                    .then(memberships.update(changed, current.version()))
                    .flatMap(stored -> events.changed(IdentityEvents.Subject.USER, stored.userId(), scope.accountId(), actor, stored.version(), "updated",
                            snapshotOf(stored)).thenReturn(stored));
        });
    }

    /** The account's groups that map identity provider groups, the ones a sync could hand back. */
    public Mono<Set<String>> mappedGroupIds(AccountScope scope) {
        return groups.findAll(scope).filter(g -> !g.idpGroups().isEmpty()).map(Group::groupId).collect(Collectors.toSet());
    }

    /**
     * Applies the identity provider's view, {@code mappedNow} (the account's groups the person's
     * identity provider groups map to now), by the sync rule. The owners group of the account's
     * last active owner is kept, not removed, and the answer lists it as kept. A membership that
     * moved on meanwhile is read again. Emits {@code UserChanged updated} when a group was added or
     * removed; the answer's membership is the stored one.
     */
    public Mono<Membership.Synced> sync(AccountScope scope, Membership current, Collection<String> mappedNow, String actor) {
        return sync(scope, current, mappedNow, actor, 3);
    }

    private Mono<Membership.Synced> sync(AccountScope scope, Membership current, Collection<String> mappedNow, String actor, int attempts) {
        Instant now = clock.instant();
        Membership.Synced trial = current.synced(mappedNow, Set.of(), now);
        if (!trial.changed()) {
            return Mono.just(trial);
        }
        return lastOwnersGroup(scope, current, trial.removed()).map(Set::of).defaultIfEmpty(Set.of()).flatMap(keep -> {
            Membership.Synced synced = keep.isEmpty() ? trial : current.synced(mappedNow, keep, now);
            if (!synced.changed()) {
                return Mono.just(synced);
            }
            return memberships.update(synced.membership(), current.version())
                    .flatMap(stored -> events.changed(IdentityEvents.Subject.USER, stored.userId(), scope.accountId(), actor, stored.version(), "updated",
                            snapshotOf(stored)).thenReturn(new Membership.Synced(stored, synced.added(), synced.removed(), synced.kept())))
                    .switchIfEmpty(Mono.defer(() -> attempts <= 1 ? Mono.error(new ConflictException("the user moved on; read it again"))
                            : memberships.find(scope, current.userId()).flatMap(again -> sync(scope, again, mappedNow, actor, attempts - 1))));
        });
    }

    /** The owners group's id when {@code removed} takes it from the account's last active owner; empty otherwise. */
    private Mono<String> lastOwnersGroup(AccountScope scope, Membership current, List<String> removed) {
        if (removed.isEmpty() || current.status() != UserStatus.active) {
            return Mono.empty();
        }
        return groups.findByName(scope, ManagedIam.OWNERS)
                .filter(owners -> removed.contains(owners.groupId()))
                .filterWhen(owners -> memberships.findByGroup(scope, owners.groupId())
                        .filter(m -> m.status() == UserStatus.active && !m.userId().equals(current.userId()))
                        .hasElements()
                        .map(others -> !others))
                .map(Group::groupId);
    }

    /** Puts a user in a group, no-op when they are in it. */
    public Mono<Membership> join(AccountScope scope, Membership current, String groupId, String actor) {
        if (current.inGroup(groupId)) {
            return Mono.just(current);
        }
        List<String> ids = new ArrayList<>(current.groupIds());
        ids.add(groupId);
        return setGroups(scope, current, List.copyOf(ids), actor);
    }

    public Mono<Membership> leave(AccountScope scope, Membership current, String groupId, String actor) {
        if (!current.inGroup(groupId)) {
            return Mono.just(current);
        }
        return setGroups(scope, current, current.groupIds().stream().filter(id -> !id.equals(groupId)).toList(), actor);
    }

    /** Makes the members of a group exactly these users. */
    public Mono<Void> setMembers(AccountScope scope, Group group, List<String> userIds, String actor) {
        return memberships.findByGroup(scope, group.groupId()).collectList().flatMap(inGroup -> {
            List<String> current = inGroup.stream().map(Membership::userId).toList();
            Flux<Membership> leaving = Flux.fromIterable(inGroup).filter(m -> !userIds.contains(m.userId()))
                    .concatMap(m -> leave(scope, m, group.groupId(), actor));
            Flux<Membership> joining = Flux.fromIterable(userIds).filter(id -> !current.contains(id))
                    .concatMap(id -> memberships.find(scope, id)
                            .switchIfEmpty(Mono.error(new com.iaf.console.kit.errors.InvalidInputException("the members are not valid",
                                    List.of(com.iaf.console.kit.errors.Problem.error("unknown", "members", "'" + id + "' is no user of this account")))))
                            .flatMap(m -> join(scope, m, group.groupId(), actor)));
            return leaving.thenMany(joining).then();
        });
    }

    /**
     * The guardrail: a change that would leave the owners group without an active member is a
     * 409. Checked when a membership leaves the owners group, is suspended, or is removed.
     */
    public Mono<Void> guardOwners(AccountScope scope, Membership before, Membership after) {
        return groups.findByName(scope, ManagedIam.OWNERS).flatMap(owners -> {
            boolean wasOwner = before.inGroup(owners.groupId()) && before.status() == UserStatus.active;
            boolean staysOwner = after != null && after.inGroup(owners.groupId()) && after.status() == UserStatus.active;
            if (!wasOwner || staysOwner) {
                return Mono.empty();
            }
            return memberships.findByGroup(scope, owners.groupId())
                    .filter(m -> m.status() == UserStatus.active && !m.userId().equals(before.userId()))
                    .count()
                    .flatMap(others -> others > 0 ? Mono.empty()
                            : Mono.error(new ConflictException("the owners group keeps at least one active member")));
        });
    }

    /** The user as the events carry them: the membership's fields, no secrets. */
    public static java.util.Map<String, Object> snapshotOf(Membership m) {
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("userId", m.userId());
        snapshot.put("email", m.email());
        if (m.name() != null) {
            snapshot.put("name", m.name());
        }
        snapshot.put("status", m.status().name());
        snapshot.put("groupIds", m.groupIds());
        if (!m.idpGroupIds().isEmpty()) {
            snapshot.put("idpGroupIds", m.idpGroupIds());
        }
        if (!m.excludedGroupIds().isEmpty()) {
            snapshot.put("excludedGroupIds", m.excludedGroupIds());
        }
        snapshot.put("version", m.version());
        return snapshot;
    }
}

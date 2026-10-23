package com.iaf.console.identity.iam.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

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
 * every path shares: the owners group keeps at least one active member.
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

    /** Sets a membership's groups; empty when the membership moved on. Emits {@code UserChanged}. */
    public Mono<Membership> setGroups(AccountScope scope, Membership current, List<String> groupIds, String actor) {
        if (current.groupIds().equals(groupIds)) {
            return Mono.just(current);
        }
        Membership changed = current.changed(null, null, groupIds, clock.instant());
        return guardOwners(scope, current, changed)
                .then(memberships.update(changed, current.version()))
                .flatMap(stored -> events.changed(IdentityEvents.Subject.USER, stored.userId(), scope.accountId(), actor, stored.version(), "updated",
                        snapshotOf(stored)).thenReturn(stored));
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
        snapshot.put("version", m.version());
        return snapshot;
    }
}

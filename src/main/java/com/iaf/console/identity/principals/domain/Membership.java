package com.iaf.console.identity.principals.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A user in an account: their state there and the groups (by id) they are in. {@code version} is
 * the user's version within the account, the one the events and {@code If-Match} carry. The email
 * and name are copied from the user so an account's list can search and sort by them.
 *
 * <p>{@code idpGroupIds} are the groups the person holds because an identity provider group of
 * theirs maps to the group ({@code Group.idpGroups}): sign-in and the nightly sync add and remove
 * them as the provider's groups change. Every other group they hold is an admin's (an admin, an
 * approved access request or the account's provisioning put them there), and no sync removes it.
 * {@code excludedGroupIds} are mapped groups an admin or an access review took away: sign-in and
 * the sync leave them out until an admin adds the group back. Every change of the groups goes
 * through one of two rules: {@link #changed} (an admin's or a review's) and {@link #synced} (the
 * identity provider's). A derived group is always a held one and an excluded group never is, and
 * a membership stored before these lists existed reads with both empty.
 */
public record Membership(String accountId, String userId, String email, String name, UserStatus status, List<String> groupIds, long version,
        Instant joinedAt, Instant updatedAt, List<String> idpGroupIds, List<String> excludedGroupIds) {

    public Membership {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
        List<String> held = groupIds;
        idpGroupIds = idpGroupIds == null ? List.of() : idpGroupIds.stream().filter(held::contains).distinct().toList();
        excludedGroupIds = excludedGroupIds == null ? List.of() : excludedGroupIds.stream().filter(g -> !held.contains(g)).distinct().toList();
    }

    /** The membership an admin (or the account's provisioning) creates: every group is the admin's. */
    public static Membership create(String accountId, User user, List<String> groupIds, Instant now) {
        return new Membership(accountId, user.userId(), user.email(), user.name(), UserStatus.active, groupIds, 1, now, now, List.of(), List.of());
    }

    /** The membership sign-in or the sync creates: every group is one the person's identity provider groups map to. */
    public static Membership mapped(String accountId, User user, List<String> groupIds, Instant now) {
        return new Membership(accountId, user.userId(), user.email(), user.name(), UserStatus.active, groupIds, 1, now, now, groupIds, List.of());
    }

    /**
     * The admin rule ({@link #changed(String, UserStatus, List, Collection, Instant)}) without the
     * account's mapped groups at hand: a group taken away becomes excluded only when the person held
     * it through the identity provider. The application changes groups through
     * {@code Memberships}, which knows the mapped groups.
     */
    public Membership changed(String newName, UserStatus newStatus, List<String> newGroupIds, Instant now) {
        return changed(newName, newStatus, newGroupIds, List.of(), now);
    }

    /**
     * An admin's or an access review's change; a null keeps the name, the status or the groups, and
     * the version grows by one. The groups change by the admin rule: a group taken away becomes
     * excluded when the person held it through the identity provider or when it is one of
     * {@code mappedGroupIds} (the account's groups that map identity provider groups, which a sync
     * would otherwise hand back), and it is no longer derived; a group added loses any exclusion and
     * counts as the admin's; a group kept stays what it was.
     */
    public Membership changed(String newName, UserStatus newStatus, List<String> newGroupIds, Collection<String> mappedGroupIds, Instant now) {
        List<String> groups = groupIds;
        List<String> derived = idpGroupIds;
        List<String> excluded = excludedGroupIds;
        if (newGroupIds != null) {
            List<String> next = List.copyOf(newGroupIds);
            groups = next;
            derived = idpGroupIds.stream().filter(next::contains).toList();
            List<String> stillExcluded = new ArrayList<>(excludedGroupIds.stream().filter(g -> !next.contains(g)).toList());
            groupIds.stream().filter(g -> !next.contains(g) && (idpGroupIds.contains(g) || mappedGroupIds.contains(g))).forEach(stillExcluded::add);
            excluded = stillExcluded;
        }
        return new Membership(accountId, userId, email, newName == null ? name : newName, newStatus == null ? status : newStatus, groups, version + 1,
                joinedAt, now, derived, excluded);
    }

    /** The admin rule ({@link #changed}) for the groups alone. */
    public Membership regrouped(List<String> newGroupIds, Collection<String> mappedGroupIds, Instant now) {
        return changed(null, null, Objects.requireNonNull(newGroupIds, "newGroupIds"), mappedGroupIds, now);
    }

    /**
     * The identity provider's rule, given {@code mappedNow}: the account's groups the person's
     * identity provider groups map to now. Each mapped group the person neither holds nor is
     * excluded from is added as derived; each derived group no longer mapped is removed, unless it
     * is in {@code keep} (the owners group of an account's last active owner), which leaves it held
     * and derived and reports it as kept; a group an admin added is never removed. The version grows
     * by one only when a group was added or removed.
     */
    public Synced synced(Collection<String> mappedNow, Collection<String> keep, Instant now) {
        List<String> added = mappedNow.stream().distinct().filter(g -> !groupIds.contains(g) && !excludedGroupIds.contains(g)).toList();
        List<String> stale = idpGroupIds.stream().filter(g -> !mappedNow.contains(g)).toList();
        List<String> kept = stale.stream().filter(keep::contains).toList();
        List<String> removed = stale.stream().filter(g -> !keep.contains(g)).toList();
        if (added.isEmpty() && removed.isEmpty()) {
            return new Synced(this, List.of(), List.of(), kept);
        }
        List<String> groups = new ArrayList<>(groupIds);
        groups.removeAll(removed);
        groups.addAll(added);
        List<String> derived = new ArrayList<>(idpGroupIds);
        derived.removeAll(removed);
        derived.addAll(added);
        return new Synced(new Membership(accountId, userId, email, name, status, groups, version + 1, joinedAt, now, derived, excludedGroupIds), added,
                removed, kept);
    }

    public Membership withUserFields(String newEmail, String newName) {
        return new Membership(accountId, userId, newEmail, newName, status, groupIds, version, joinedAt, updatedAt, idpGroupIds, excludedGroupIds);
    }

    public boolean inGroup(String groupId) {
        return groupIds.contains(groupId);
    }

    /**
     * What a sync did: the membership after it (this one, version and all, when nothing changed),
     * the groups it added and removed, and the removals it left undone because the caller kept them.
     */
    public record Synced(Membership membership, List<String> added, List<String> removed, List<String> kept) {

        public Synced {
            Objects.requireNonNull(membership, "membership");
            added = List.copyOf(added);
            removed = List.copyOf(removed);
            kept = List.copyOf(kept);
        }

        public boolean changed() {
            return !added.isEmpty() || !removed.isEmpty();
        }
    }
}

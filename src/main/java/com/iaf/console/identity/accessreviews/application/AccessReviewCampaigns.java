package com.iaf.console.identity.accessreviews.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.iaf.console.identity.accessreviews.domain.AccessReview;
import com.iaf.console.identity.accessreviews.domain.AccessReviewItem;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewItemStore;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.Memberships;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.domain.UserStatus;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetrySpec;

/**
 * What the commands and the sweep share about a campaign: starting one over the account's active
 * memberships, recording a decision, taking a revoked or expired group off a membership (through
 * {@link Memberships#setGroups}, so the owners guardrail and the {@code UserChanged} event come
 * with it), and the campaign's counts after each change, published as {@code AccessReviewChanged}
 * with the version that grows per campaign.
 */
public final class AccessReviewCampaigns {

    /** Who the sweep acts as: the starter of a scheduled campaign and the decider of what the due date expires. */
    public static final String SYSTEM = "system:access-review";
    /** The kind of an access review in an iam wfrn. */
    public static final String KIND = "access-review";
    /** The comment on the last active owner's owners item, which the due date keeps rather than expires. */
    public static final String LAST_OWNER_KEPT = "kept at the due date: the account's last active owner keeps the owners group";
    static final String LAST_OWNER = "the account's last active owner keeps the owners group";
    private static final int ATTEMPTS = 3;

    private final AccessReviewStore reviews;
    private final AccessReviewItemStore items;
    private final MembershipStore memberships;
    private final GroupStore groups;
    private final RoleStore roles;
    private final Memberships membership;
    private final IdentityEvents events;
    private final Ids ids;

    public AccessReviewCampaigns(AccessReviewStore reviews, AccessReviewItemStore items, MembershipStore memberships, GroupStore groups, RoleStore roles,
            Memberships membership, IdentityEvents events, Ids ids) {
        this.reviews = reviews;
        this.items = items;
        this.memberships = memberships;
        this.groups = groups;
        this.roles = roles;
        this.membership = membership;
        this.events = events;
        this.ids = ids;
    }

    /** What the due date did to a campaign: the items it expired, and whether it closed the campaign. */
    public record Expiry(int expired, boolean closed) {
    }

    /** The name of a campaign started without one. */
    public static String defaultName(AccessReview.Trigger trigger, Instant now) {
        String date = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        return trigger == AccessReview.Trigger.scheduled ? "Quarterly access review " + date : "Access review " + date;
    }

    /**
     * Starts a campaign: one item per person and group of the account's active memberships, with
     * the role names the group carries now. A 409 naming the open campaign when there is one,
     * also when another start won the race to it.
     */
    public Mono<AccessReview> start(AccountScope scope, String name, int dueInDays, AccessReview.Trigger trigger, String actor, Instant now) {
        return reviews.findOpen(scope)
                .flatMap(open -> Mono.<AccessReview>error(openAlready(open)))
                .switchIfEmpty(Mono.defer(() -> {
                    String reviewId = ids.accessReview();
                    return itemsOf(scope, reviewId).flatMap(made -> {
                        AccessReview review = AccessReview.start(reviewId, scope.accountId(), name, trigger, actor, now, now.plus(Duration.ofDays(dueInDays)),
                                AccessReview.Counts.of(made));
                        // the items first: until the campaign is stored nothing reads them, so a start that loses the race leaves nothing visible
                        return items.insertAll(made).then(reviews.insert(review))
                                .onErrorResume(ConflictException.class, lost -> items.deleteByReview(scope, reviewId)
                                        .then(reviews.findOpen(scope))
                                        .flatMap(open -> Mono.<AccessReview>error(openAlready(open)))
                                        .switchIfEmpty(Mono.error(lost)));
                    });
                }))
                .flatMap(stored -> published(scope, stored, actor, "created", null));
    }

    /**
     * Records a decision on a pending item. A revoke takes the group off the person's membership
     * (nothing changes when they no longer hold it), never the last active owner's owners group
     * (a 409); when the change fails the item is pending again. The campaign's counts follow, and
     * it closes when nothing is pending.
     */
    public Mono<AccessReviewItem> decide(AccountScope scope, AccessReviewItem item, AccessReviewItem.Decision decision, String actor, String comment,
            Instant now) {
        boolean revoke = decision == AccessReviewItem.Decision.revoke;
        Mono<Void> allowed = !revoke ? Mono.empty()
                : lastOwner(scope, item, now).flatMap(last -> last ? Mono.<Void>error(new ConflictException(LAST_OWNER)) : Mono.<Void>empty());
        return allowed
                .then(Mono.defer(() -> items.update(item.decided(decision, actor, comment, now), item.version())))
                .switchIfEmpty(Mono.error(() -> new ConflictException("the item was decided meanwhile; read it again")))
                .flatMap(claimed -> (revoke ? removeGroup(scope, claimed, actor) : Mono.just(Boolean.FALSE))
                        .onErrorResume(failed -> items.update(claimed.reopened(), claimed.version()).then(Mono.<Boolean>error(failed)))
                        .thenReturn(claimed))
                .flatMap(claimed -> counted(scope, claimed, actor, now).thenReturn(claimed));
    }

    /**
     * Expires a due campaign: each pending item loses its access as a revoke would (decided by
     * {@value #SYSTEM}), except the last active owner's owners group, which is kept with a
     * comment; then the campaign closes. An item whose change fails is pending again and the
     * campaign stays open for the next sweep.
     */
    public Mono<Expiry> expire(AccountScope scope, AccessReview review, Instant now) {
        return items.findPending(scope, review.reviewId())
                .concatMap(item -> expireItem(scope, item, now))
                .reduce(0, Integer::sum)
                .flatMap(expired -> close(scope, review.reviewId(), now).map(closed -> new Expiry(expired, true)).defaultIfEmpty(new Expiry(expired, false)));
    }

    /**
     * Cancels an open campaign; its pending items stay pending. A 409 when it is closed or
     * cancelled, or past its due date, when what is pending expires instead.
     */
    public Mono<AccessReview> cancel(AccountScope scope, String reviewId, String actor, Instant now) {
        return Mono.defer(() -> reviews.find(scope, reviewId)
                        .switchIfEmpty(Mono.error(() -> NotFoundException.of("access review", reviewId)))
                        .flatMap(current -> {
                            if (!current.isOpen()) {
                                return Mono.<AccessReview>error(new ConflictException("the access review is " + current.status()));
                            }
                            if (current.isDue(now)) {
                                return Mono.<AccessReview>error(pastDue(current));
                            }
                            return reviews.update(current.cancelled(now), current.version()).switchIfEmpty(Mono.error(MovedOn::new));
                        }))
                .retryWhen(movedOn("the access review moved on; read it again"))
                .flatMap(stored -> published(scope, stored, actor, "updated", null));
    }

    /**
     * The account closed: its open campaign is cancelled, past its due date too, so the sweep takes
     * no group off anyone in a closed account. With none open there is nothing to do, so a closure
     * that runs again changes nothing.
     */
    public Mono<Void> accountClosed(AccountScope scope, String actor, Instant now) {
        return Mono.defer(() -> reviews.findOpen(scope)
                        .flatMap(open -> reviews.update(open.cancelled(now), open.version()).switchIfEmpty(Mono.error(MovedOn::new))))
                .retryWhen(movedOn("the access review moved on; close the account again"))
                .flatMap(stored -> published(scope, stored, actor, "updated", null))
                .then();
    }

    /** The 409 of a second open campaign: it names the open one. */
    static ConflictException openAlready(AccessReview open) {
        return new ConflictException("an access review of the account is open: " + open.reviewId(), Map.of("reviewId", open.reviewId()));
    }

    /** The 409 of a decision or a cancel after the due date: the sweep closes the campaign and expires what is pending. */
    static ConflictException pastDue(AccessReview review) {
        return new ConflictException("the access review was due at " + review.dueAt() + ": what is pending expires");
    }

    private Mono<List<AccessReviewItem>> itemsOf(AccountScope scope, String reviewId) {
        return Mono.zip(memberships.findAll(scope).collectList(), groups.findAll(scope).collectMap(Group::groupId),
                        roles.findAll(scope).collectMap(Role::roleId, Role::name))
                .map(t -> {
                    Map<String, Group> groupsById = t.getT2();
                    Map<String, String> roleNames = t.getT3();
                    List<AccessReviewItem> made = new ArrayList<>();
                    // suspended people hold no access, so there is nothing of theirs to review
                    List<Membership> active = t.getT1().stream().filter(m -> m.status() == UserStatus.active).sorted(Comparator.comparing(Membership::userId))
                            .toList();
                    for (Membership m : active) {
                        for (String groupId : new LinkedHashSet<>(m.groupIds())) {
                            Group g = groupsById.get(groupId);
                            if (g == null) {
                                continue;
                            }
                            List<String> carried = g.roles().stream().filter(roleNames::containsKey).map(roleNames::get).toList();
                            made.add(AccessReviewItem.pending(ids.accessReviewItem(), scope.accountId(), reviewId, m.userId(), m.email(), m.name(), g.groupId(),
                                    g.name(), carried));
                        }
                    }
                    return made;
                });
    }

    private Mono<Integer> expireItem(AccountScope scope, AccessReviewItem item, Instant now) {
        return lastOwner(scope, item, now).flatMap(last -> last
                ? items.update(item.decided(AccessReviewItem.Decision.keep, SYSTEM, LAST_OWNER_KEPT, now), item.version()).thenReturn(0)
                : items.update(item.decided(AccessReviewItem.Decision.expired, SYSTEM, null, now), item.version())
                        .flatMap(claimed -> removeGroup(scope, claimed, SYSTEM)
                                .onErrorResume(failed -> items.update(claimed.reopened(), claimed.version()).then(Mono.<Boolean>error(failed)))
                                .thenReturn(1))
                        // decided meanwhile
                        .defaultIfEmpty(0));
    }

    /** Whether taking the item's group off its person would leave the owners group without an active member. */
    private Mono<Boolean> lastOwner(AccountScope scope, AccessReviewItem item, Instant now) {
        return memberships.find(scope, item.userId())
                .filter(m -> m.inGroup(item.groupId()))
                .flatMap(m -> membership.guardOwners(scope, m, m.changed(null, null, without(m, item.groupId()), now))
                        .thenReturn(Boolean.FALSE)
                        .onErrorResume(ConflictException.class, e -> Mono.just(Boolean.TRUE)))
                .defaultIfEmpty(Boolean.FALSE);
    }

    /** Takes the item's group off its person's membership, with the usual UserChanged; false when they no longer hold it, so nothing changed. */
    private Mono<Boolean> removeGroup(AccountScope scope, AccessReviewItem item, String actor) {
        return Mono.defer(() -> memberships.find(scope, item.userId())
                        .filter(m -> m.inGroup(item.groupId()))
                        .flatMap(m -> membership.setGroups(scope, m, without(m, item.groupId()), actor)
                                .switchIfEmpty(Mono.error(MovedOn::new))
                                .thenReturn(Boolean.TRUE))
                        .defaultIfEmpty(Boolean.FALSE))
                .retryWhen(movedOn("the user " + item.userId() + " changed meanwhile; decide again"));
    }

    /** The campaign's counts after a decision, closed when nothing is pending, published with the item decided. */
    private Mono<AccessReview> counted(AccountScope scope, AccessReviewItem decided, String actor, Instant now) {
        String reviewId = decided.reviewId();
        return Mono.defer(() -> reviews.find(scope, reviewId)
                        .switchIfEmpty(Mono.error(() -> NotFoundException.of("access review", reviewId)))
                        .flatMap(current -> items.countByDecision(scope, reviewId)
                                .flatMap(counts -> reviews.update(current.counted(AccessReview.Counts.of(counts), now), current.version())))
                        .switchIfEmpty(Mono.error(MovedOn::new)))
                .retryWhen(movedOn("the access review moved on; read it again"))
                .flatMap(stored -> published(scope, stored, actor, "updated", decided));
    }

    /** Closes a due campaign once nothing of it is pending, published as {@code expired}; empty when it is no longer open or something is pending still. */
    private Mono<AccessReview> close(AccountScope scope, String reviewId, Instant now) {
        return Mono.defer(() -> reviews.find(scope, reviewId)
                        .filter(AccessReview::isOpen)
                        .flatMap(current -> items.countByDecision(scope, reviewId)
                                .map(counts -> AccessReview.Counts.of(counts))
                                .filter(counts -> counts.pending() == 0)
                                .flatMap(counts -> reviews.update(current.expired(counts, now), current.version()).switchIfEmpty(Mono.error(MovedOn::new)))))
                .retryWhen(movedOn("the access review moved on"))
                .flatMap(stored -> published(scope, stored, SYSTEM, "expired", null));
    }

    private Mono<AccessReview> published(AccountScope scope, AccessReview review, String actor, String action, AccessReviewItem item) {
        return events.changed(IdentityEvents.Subject.ACCESS_REVIEW, review.reviewId(), scope.accountId(), actor, review.version(), action,
                new AccessReviewSnapshot(review, item)).thenReturn(review);
    }

    private static List<String> without(Membership m, String groupId) {
        return m.groupIds().stream().filter(id -> !id.equals(groupId)).toList();
    }

    private static RetrySpec movedOn(String exhausted) {
        return Retry.max(ATTEMPTS - 1L).filter(MovedOn.class::isInstance).onRetryExhaustedThrow((spec, signal) -> new ConflictException(exhausted));
    }

    /** A write found the record at another version than the one it read: read again and retry. */
    private static final class MovedOn extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MovedOn() {
            super("moved on", null, false, false);
        }
    }
}

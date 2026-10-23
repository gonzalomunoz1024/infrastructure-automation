package com.iaf.console.identity.accessreviews.application;

import java.time.Clock;
import java.time.Instant;

import com.iaf.console.identity.accessreviews.domain.AccessReview;
import com.iaf.console.identity.accessreviews.domain.AccessReviewItem;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewItemStore;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewStore;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.common.web.Denial;
import com.iaf.console.identity.common.web.DeniedException;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * The writes on access reviews, an account admin's: {@code access-review:create} starts a
 * campaign (one open per account), {@code access-review:decide} keeps or revokes an item, once,
 * and {@code access-review:cancel} stops a campaign; past the due date neither is taken, so what is
 * pending expires. Nobody decides an item about themselves: the person reviewed is refused on
 * {@code :decide} with a denial that says so.
 */
public final class AccessReviewCommands {

    public static final int DEFAULT_DUE_IN_DAYS = 14;
    public static final int MAX_DUE_IN_DAYS = 90;
    static final int MAX_NAME = 200;
    static final int MAX_COMMENT = 2000;
    static final String OWN_ACCESS = "nobody reviews their own access";

    private final AccessReviewStore reviews;
    private final AccessReviewItemStore items;
    private final AccountStore accounts;
    private final AccessReviewCampaigns campaigns;
    private final AccessGuard guard;
    private final IamSupport support;
    private final Clock clock;

    public AccessReviewCommands(AccessReviewStore reviews, AccessReviewItemStore items, AccountStore accounts, AccessReviewCampaigns campaigns, AccessGuard guard,
            IamSupport support, Clock clock) {
        this.reviews = reviews;
        this.items = items;
        this.accounts = accounts;
        this.campaigns = campaigns;
        this.guard = guard;
        this.support = support;
        this.clock = clock;
    }

    public Mono<AccessReview> start(Caller caller, String accountId, StartAccessReview input) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "access-review:create", support.anyResource(accountId, AccessReviewCampaigns.KIND))
                        .thenReturn(scope))
                .flatMap(scope -> {
                    String name = input == null || input.name() == null ? null : input.name().strip();
                    Integer days = input == null ? null : input.dueInDays();
                    new Names.Check()
                            .require(name == null || !name.isEmpty() && name.length() <= MAX_NAME, "name", "name is 1 to 200 characters")
                            .require(days == null || days >= 1 && days <= MAX_DUE_IN_DAYS, "dueInDays", "dueInDays is 1 to 90")
                            .done("the access review");
                    return accounts.find(scope)
                            .switchIfEmpty(Mono.error(() -> NotFoundException.of("account", accountId)))
                            .flatMap(account -> account.isActive() ? Mono.just(account)
                                    : Mono.error(new ConflictException("the account is " + account.status())))
                            .flatMap(account -> {
                                Instant now = clock.instant();
                                return campaigns.start(scope, name == null ? AccessReviewCampaigns.defaultName(AccessReview.Trigger.manual, now) : name,
                                        days == null ? DEFAULT_DUE_IN_DAYS : days, AccessReview.Trigger.manual, caller.actor(), now);
                            });
                });
    }

    public Mono<AccessReviewItem> decide(Caller caller, String accountId, String reviewId, String itemId, DecideAccessReviewItem input) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "access-review:decide", support.resource(accountId, AccessReviewCampaigns.KIND, reviewId))
                        .thenReturn(scope))
                .flatMap(scope -> {
                    String decision = input == null ? null : input.decision();
                    String comment = input == null ? null : input.comment();
                    boolean keep = "keep".equals(decision);
                    new Names.Check().require(keep || "revoke".equals(decision), "decision", "decision is keep or revoke")
                            .require(comment == null || comment.length() <= MAX_COMMENT, "comment", "comment is at most 2000 characters")
                            .done("the decision");
                    return reviews.find(scope, reviewId)
                            .switchIfEmpty(Mono.error(() -> NotFoundException.of("access review", reviewId)))
                            .flatMap(review -> items.find(scope, reviewId, itemId)
                                    .switchIfEmpty(Mono.error(() -> NotFoundException.of("access review item", itemId)))
                                    .flatMap(item -> {
                                        if (caller.isUser() && caller.id().equals(item.userId())) {
                                            return Mono.error(new DeniedException(new Denial(Denial.CONSOLE_IAM, "access-review:decide",
                                                    support.resource(accountId, AccessReviewCampaigns.KIND, reviewId), OWN_ACCESS, null, null, null, Boolean.FALSE,
                                                    null)));
                                        }
                                        Instant now = clock.instant();
                                        if (!review.isOpen()) {
                                            return Mono.error(new ConflictException("the access review is " + review.status()));
                                        }
                                        if (review.isDue(now)) {
                                            return Mono.error(AccessReviewCampaigns.pastDue(review));
                                        }
                                        if (!item.isPending()) {
                                            return Mono.error(new ConflictException("the item is decided: " + item.decision()));
                                        }
                                        return campaigns.decide(scope, item, keep ? AccessReviewItem.Decision.keep : AccessReviewItem.Decision.revoke,
                                                caller.actor(), comment, now);
                                    }));
                });
    }

    public Mono<AccessReview> cancel(Caller caller, String accountId, String reviewId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "access-review:cancel", support.resource(accountId, AccessReviewCampaigns.KIND, reviewId))
                        .thenReturn(scope))
                .flatMap(scope -> campaigns.cancel(scope, reviewId, caller.actor(), clock.instant()));
    }
}

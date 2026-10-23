package com.iaf.console.identity.accessreviews.application;

import com.iaf.console.identity.accessreviews.domain.AccessReview;
import com.iaf.console.identity.accessreviews.domain.AccessReviewItem;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewItemStore;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewStore;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * The reads on access reviews: for whoever may {@code access-review:list} the campaigns and
 * {@code access-review:get} one and its items, which the managed policies give account admins
 * and auditors only (viewers and operators have them carved out), and the internal principal.
 */
public final class AccessReviewQueries {

    private final AccessReviewStore reviews;
    private final AccessReviewItemStore items;
    private final AccessGuard guard;
    private final IamSupport support;

    public AccessReviewQueries(AccessReviewStore reviews, AccessReviewItemStore items, AccessGuard guard, IamSupport support) {
        this.reviews = reviews;
        this.items = items;
        this.guard = guard;
        this.support = support;
    }

    /** The account's campaigns, the one started last first. */
    public Mono<Page<AccessReview>> list(Caller caller, String accountId, int limit, String after) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "access-review:list", support.anyResource(accountId, AccessReviewCampaigns.KIND))
                        .thenReturn(scope))
                .flatMap(scope -> reviews.list(scope, limit, after));
    }

    public Mono<AccessReview> get(Caller caller, String accountId, String reviewId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "access-review:get", support.resource(accountId, AccessReviewCampaigns.KIND, reviewId))
                        .thenReturn(scope))
                .flatMap(scope -> reviews.find(scope, reviewId).switchIfEmpty(Mono.error(() -> NotFoundException.of("access review", reviewId))));
    }

    /** A campaign's items in the order they were made, of one decision when given; reading them is reading the campaign. */
    public Mono<Page<AccessReviewItem>> items(Caller caller, String accountId, String reviewId, String decision, int limit, String after) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "access-review:get", support.resource(accountId, AccessReviewCampaigns.KIND, reviewId))
                        .thenReturn(scope))
                .flatMap(scope -> {
                    AccessReviewItem.Decision wanted = decision == null ? null : parse(decision);
                    return reviews.find(scope, reviewId)
                            .switchIfEmpty(Mono.error(() -> NotFoundException.of("access review", reviewId)))
                            .flatMap(review -> items.list(scope, reviewId, wanted, limit, after));
                });
    }

    private static AccessReviewItem.Decision parse(String decision) {
        try {
            return AccessReviewItem.Decision.valueOf(decision);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("decision is pending, keep, revoke or expired");
        }
    }
}

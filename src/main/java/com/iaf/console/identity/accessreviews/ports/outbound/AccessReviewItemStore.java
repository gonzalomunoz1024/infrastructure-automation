package com.iaf.console.identity.accessreviews.ports.outbound;

import java.util.List;
import java.util.Map;

import com.iaf.console.identity.accessreviews.domain.AccessReviewItem;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where the items of the access reviews live, in the order they were made. */
public interface AccessReviewItemStore {

    /** Stores a new campaign's items; a {@link com.iaf.console.kit.errors.ConflictException} when an id exists. */
    Mono<Void> insertAll(List<AccessReviewItem> items);

    /** Replaces an item at the version given; empty when the stored version differs. */
    Mono<AccessReviewItem> update(AccessReviewItem item, long expectedVersion);

    Mono<AccessReviewItem> find(AccountScope scope, String reviewId, String itemId);

    /** One page of a campaign's items in id order; of one decision when given. */
    Mono<Page<AccessReviewItem>> list(AccountScope scope, String reviewId, AccessReviewItem.Decision decision, int limit, String after);

    /** A campaign's pending items, in id order. */
    Flux<AccessReviewItem> findPending(AccountScope scope, String reviewId);

    /** How many items a campaign has by decision; a decision no item has is absent. */
    Mono<Map<AccessReviewItem.Decision, Long>> countByDecision(AccountScope scope, String reviewId);

    /** Removes a campaign's items: what is left of a campaign whose start lost the race to another. */
    Mono<Void> deleteByReview(AccountScope scope, String reviewId);
}

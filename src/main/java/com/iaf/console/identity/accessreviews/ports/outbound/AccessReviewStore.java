package com.iaf.console.identity.accessreviews.ports.outbound;

import java.time.Instant;

import com.iaf.console.identity.accessreviews.domain.AccessReview;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where access review campaigns live: at most one open per account. */
public interface AccessReviewStore {

    /**
     * Stores a new campaign; a {@link com.iaf.console.kit.errors.ConflictException} when its id
     * exists or it is open while another campaign of the account is.
     */
    Mono<AccessReview> insert(AccessReview review);

    /** Replaces a campaign at the version given; empty when the stored version differs. */
    Mono<AccessReview> update(AccessReview review, long expectedVersion);

    Mono<AccessReview> find(AccountScope scope, String reviewId);

    /** The open campaign of the account, when there is one. */
    Mono<AccessReview> findOpen(AccountScope scope);

    /** The campaign started last, whatever its status. */
    Mono<AccessReview> findLatest(AccountScope scope);

    /** One page, the campaign started last first. */
    Mono<Page<AccessReview>> list(AccountScope scope, int limit, String after);

    /** Every open campaign, across accounts, due at or before {@code now}: what the sweep closes. */
    @GlobalRead
    Flux<AccessReview> findDue(Instant now);
}

package com.iaf.console.drift.rightsizing.ports.outbound;

import com.iaf.console.drift.rightsizing.domain.Recommendation;
import com.iaf.console.drift.rightsizing.domain.RecommendationQuery;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The recommendations: every one ever made, with at most one current (open or dismissed) per
 * resource. Every write is conditional on the revision it read, so the daily pass and a person's
 * decision never overwrite each other.
 */
public interface RecommendationStorePort {

    Mono<Recommendation> find(AccountScope scope, String recommendationId);

    /** One recommendation whatever its account, for the console's own services, which name none (ids are unique across accounts). */
    @GlobalRead("an internal caller may name no account; recommendation ids are unique across accounts")
    Mono<Recommendation> findAny(String recommendationId);

    /** A page of the account's recommendations, the largest saving first. */
    Mono<Page<Recommendation>> list(AccountScope scope, RecommendationQuery query);

    /** The account's open recommendations, for the summary. */
    Flux<Recommendation> findOpen(AccountScope scope);

    /** The resource's current recommendation, open or dismissed. */
    @GlobalRead("the utilisation pass works across accounts; the wfrn names the account")
    Mono<Recommendation> findCurrentByWfrn(String wfrn);

    /** The current recommendations of an account, or of every account when it is null: the pass looks for vanished resources. */
    @GlobalRead("the utilisation pass works across accounts")
    Flux<Recommendation> findCurrent(String accountId);

    /**
     * Writes the recommendation if the stored one is still at {@code rev} (0: not stored yet) and
     * answers it at the next revision; otherwise, or when a new one would be the resource's second
     * current one, fails with {@link Conflict}.
     */
    Mono<Recommendation> save(Recommendation recommendation);

    /** Another writer changed the recommendation since it was read, or opened another for the resource. */
    final class Conflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Conflict(String recommendationId) {
            super("the recommendation " + recommendationId + " changed since it was read, or its resource has another");
        }
    }
}

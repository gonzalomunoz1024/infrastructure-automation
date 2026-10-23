package com.iaf.console.drift.rightsizing.adapters.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.drift.rightsizing.domain.Recommendation;
import com.iaf.console.drift.rightsizing.domain.RecommendationQuery;
import com.iaf.console.drift.rightsizing.domain.RecommendationStatus;
import com.iaf.console.drift.rightsizing.ports.outbound.RecommendationStorePort;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The recommendations in memory, for the demo and the tests, with the MongoDB store's rules: writes
 * conditional on the revision, and at most one current recommendation per resource.
 */
public final class InMemoryRecommendationStore implements RecommendationStorePort {

    /** The list order: the largest saving first, then the newest. */
    static final Comparator<Recommendation> LIST_ORDER = Comparator.comparingDouble(Recommendation::savingSort).reversed()
            .thenComparing(Recommendation::recommendationId, Comparator.reverseOrder());

    private final Map<String, Recommendation> byId = new ConcurrentHashMap<>();

    @Override
    public Mono<Recommendation> find(AccountScope scope, String recommendationId) {
        return Mono.justOrEmpty(byId.get(recommendationId)).filter(r -> r.accountId().equals(scope.accountId()));
    }

    @Override
    public Mono<Recommendation> findAny(String recommendationId) {
        return Mono.justOrEmpty(byId.get(recommendationId));
    }

    @Override
    public Mono<Page<Recommendation>> list(AccountScope scope, RecommendationQuery query) {
        return Mono.fromSupplier(() -> {
            Cursor after = query.after();
            List<Recommendation> fetched = byId.values().stream()
                    .filter(r -> r.accountId().equals(scope.accountId()))
                    .filter(r -> query.status() == null || query.status() == r.status())
                    .filter(r -> query.kind() == null || query.kind() == r.kind())
                    .filter(r -> query.type() == null || query.type().equals(r.type()))
                    .filter(r -> query.wfrn() == null || query.wfrn().equals(r.wfrn()))
                    .filter(r -> after == null || isAfter(r, after))
                    .sorted(LIST_ORDER)
                    .limit(query.limit() + 1L)
                    .toList();
            return Page.from(fetched, query.limit(), r -> new Cursor(r.savingSort(), r.recommendationId()));
        });
    }

    private static boolean isAfter(Recommendation r, Cursor after) {
        double saving = ((Number) after.sortValue()).doubleValue();
        return r.savingSort() < saving || r.savingSort() == saving && r.recommendationId().compareTo(after.id()) < 0;
    }

    @Override
    public Flux<Recommendation> findOpen(AccountScope scope) {
        return Flux.fromStream(() -> byId.values().stream()
                .filter(r -> r.accountId().equals(scope.accountId()) && r.status() == RecommendationStatus.OPEN)
                .sorted(LIST_ORDER));
    }

    @Override
    public Mono<Recommendation> findCurrentByWfrn(String wfrn) {
        return Mono.justOrEmpty(byId.values().stream().filter(r -> r.wfrn().equals(wfrn) && r.active()).findFirst());
    }

    @Override
    public Flux<Recommendation> findCurrent(String accountId) {
        return Flux.fromStream(() -> byId.values().stream()
                .filter(r -> r.active() && (accountId == null || accountId.equals(r.accountId())))
                .sorted(Comparator.comparing(Recommendation::recommendationId)));
    }

    @Override
    public Mono<Recommendation> save(Recommendation recommendation) {
        return Mono.fromCallable(() -> {
            synchronized (this) {
                Recommendation stored = byId.get(recommendation.recommendationId());
                long rev = stored == null ? 0 : stored.rev();
                if (rev != recommendation.rev()) {
                    throw new Conflict(recommendation.recommendationId());
                }
                if (recommendation.active() && byId.values().stream().anyMatch(r -> r.active() && r.wfrn().equals(recommendation.wfrn())
                        && !r.recommendationId().equals(recommendation.recommendationId()))) {
                    throw new Conflict(recommendation.recommendationId());
                }
                Recommendation next = recommendation.withRev(rev + 1);
                byId.put(next.recommendationId(), next);
                return next;
            }
        });
    }
}

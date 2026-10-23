package com.iaf.console.drift.rightsizing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.iaf.console.drift.drift.domain.Money;
import com.iaf.console.drift.rightsizing.domain.Recommendation;
import com.iaf.console.drift.rightsizing.domain.RecommendationKind;
import com.iaf.console.drift.rightsizing.domain.RecommendationQuery;
import com.iaf.console.drift.rightsizing.domain.RecommendationsSummary;
import com.iaf.console.drift.rightsizing.ports.outbound.RecommendationStorePort;
import com.iaf.console.drift.rightsizing.ports.outbound.UtilisationStorePort;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** The reads of the rightsizing API: a page of recommendations, one recommendation, and the account's summary. */
public final class RecommendationQueries {

    private final RecommendationStorePort store;
    private final UtilisationStorePort utilisation;

    public RecommendationQueries(RecommendationStorePort store, UtilisationStorePort utilisation) {
        this.store = store;
        this.utilisation = utilisation;
    }

    public Mono<Page<Recommendation>> list(AccountScope scope, RecommendationQuery query) {
        return store.list(scope, query);
    }

    public Mono<Recommendation> get(AccountScope scope, String recommendationId) {
        return store.find(scope, recommendationId).switchIfEmpty(Mono.error(() -> NotFoundException.of("recommendation", recommendationId)));
    }

    /** One recommendation whatever its account, for the console's own services. */
    public Mono<Recommendation> getAny(String recommendationId) {
        return store.findAny(recommendationId).switchIfEmpty(Mono.error(() -> NotFoundException.of("recommendation", recommendationId)));
    }

    public Mono<RecommendationsSummary> summary(AccountScope scope) {
        return store.findOpen(scope).collectList()
                .zipWith(utilisation.findLastReadAt(scope).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .map(t -> summarize(t.getT1(), t.getT2().orElse(null)));
    }

    /**
     * Counts the open recommendations by kind (both kinds always named) and adds up their savings in
     * the currency most of them are priced in; savings in another currency are left out rather than
     * added as if they were the same money.
     */
    static RecommendationsSummary summarize(List<Recommendation> open, Instant lastPassAt) {
        Map<String, Integer> byKind = new LinkedHashMap<>();
        for (RecommendationKind kind : RecommendationKind.values()) {
            byKind.put(kind.id(), 0);
        }
        for (Recommendation r : open) {
            byKind.merge(r.kind().id(), 1, Integer::sum);
        }
        Map<String, List<Money>> byCurrency = open.stream().map(Recommendation::monthlySaving).filter(m -> m != null)
                .collect(Collectors.groupingBy(Money::currency));
        String currency = byCurrency.entrySet().stream()
                .max(Comparator.comparingInt((Map.Entry<String, List<Money>> e) -> e.getValue().size()).thenComparing(Map.Entry::getKey,
                        Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse("USD");
        List<Money> savings = byCurrency.getOrDefault(currency, List.of());
        BigDecimal total = savings.stream().map(Money::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String basis = savings.stream().map(Money::basis).distinct().count() == 1 ? savings.get(0).basis() : null;
        return new RecommendationsSummary(open.size(), byKind, new Money(total, currency, basis), lastPassAt);
    }
}

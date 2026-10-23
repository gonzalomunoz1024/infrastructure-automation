package com.iaf.console.bff.insights.application;

import java.util.List;
import java.util.Map;

import com.iaf.console.bff.insights.domain.Counted;
import com.iaf.console.bff.session.domain.Session;

import reactor.core.publisher.Mono;

/**
 * The search index's counts across accounts as the console-internal principal (the indexer's
 * {@code GET /v1/counts} with {@code accountId} repeated, at most 200), for accounts the caller has
 * already decided the person may read: {@code filters} are the counts' own ({@code q}, {@code type},
 * {@code state}, {@code drift}, {@code region}) and {@code groupBy} is null or one of its keys.
 */
@FunctionalInterface
public interface InventoryCounts {

    Mono<Counted> countAcross(Session session, List<String> accountIds, Map<String, String> filters, String groupBy);
}

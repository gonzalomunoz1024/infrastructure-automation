package com.iaf.console.drift.scorecards.adapters.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.drift.scorecards.domain.ResourceEvaluation;
import com.iaf.console.drift.scorecards.domain.RuleKey;
import com.iaf.console.drift.scorecards.domain.RuleResult;
import com.iaf.console.drift.scorecards.domain.RuleStatus;
import com.iaf.console.drift.scorecards.domain.Views;
import com.iaf.console.drift.scorecards.ports.outbound.EvaluationStorePort;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Evaluations in memory, for the demo and the tests; writes are conditional on the version as in MongoDB. */
public final class InMemoryEvaluationStore implements EvaluationStorePort {

    private final Map<String, ResourceEvaluation> byWfrn = new ConcurrentHashMap<>();

    @Override
    public Mono<ResourceEvaluation> find(AccountScope scope, String wfrn) {
        return Mono.justOrEmpty(byWfrn.get(wfrn)).filter(e -> e.accountId().equals(scope.accountId()));
    }

    @Override
    public Mono<ResourceEvaluation> findByWfrn(String wfrn) {
        return Mono.justOrEmpty(byWfrn.get(wfrn));
    }

    @Override
    public Mono<Boolean> save(ResourceEvaluation e) {
        return Mono.fromSupplier(() -> store(e));
    }

    private synchronized boolean store(ResourceEvaluation e) {
        ResourceEvaluation current = byWfrn.get(e.wfrn());
        long stored = current == null ? 0 : current.version();
        if (stored != e.version() - 1) {
            return false;
        }
        byWfrn.put(e.wfrn(), e);
        return true;
    }

    @Override
    public Mono<ResourceEvaluation> delete(String wfrn) {
        return Mono.fromSupplier(() -> byWfrn.remove(wfrn));
    }

    @Override
    public Mono<Page<Views.Evaluation>> listFailing(AccountScope scope, RuleKey rule, String type, int limit, Cursor after) {
        return Mono.fromSupplier(() -> {
            Instant cursorAt = after == null ? null : Instant.parse(after.sortText());
            List<Views.Evaluation> fetched = byWfrn.values().stream()
                    .filter(e -> e.accountId().equals(scope.accountId()) && (type == null || type.equals(e.type())))
                    .map(e -> view(e, e.result(rule)))
                    .filter(v -> v != null)
                    .sorted(Comparator.comparing(Views.Evaluation::since).thenComparing(Views.Evaluation::wfrn))
                    .filter(v -> cursorAt == null || v.since().isAfter(cursorAt) || v.since().equals(cursorAt) && v.wfrn().compareTo(after.id()) > 0)
                    .limit(limit + 1L)
                    .toList();
            return Page.from(fetched, limit, v -> new Cursor(v.since().toString(), v.wfrn()));
        });
    }

    static Views.Evaluation view(ResourceEvaluation e, RuleResult r) {
        if (r == null || r.status() != RuleStatus.FAIL) {
            return null;
        }
        return new Views.Evaluation(e.wfrn(), e.accountId(), e.region(), e.type(), e.name(), r.pack(), r.ruleId(), r.severity(), r.status(), r.message(),
                r.since(), e.evaluatedAt());
    }

    @Override
    public Flux<ResourceEvaluation> listAll(AccountScope scope) {
        return Flux.fromIterable(List.copyOf(byWfrn.values())).filter(e -> e.accountId().equals(scope.accountId()));
    }

    @Override
    public Flux<String> listAccounts() {
        return Flux.fromIterable(List.copyOf(byWfrn.values())).map(ResourceEvaluation::accountId).distinct();
    }
}

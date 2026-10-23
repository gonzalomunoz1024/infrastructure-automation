package com.iaf.console.drift.scorecards.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.application.ResourceListener;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.scorecards.domain.EvaluationInput;
import com.iaf.console.drift.scorecards.domain.ResourceEvaluation;
import com.iaf.console.drift.scorecards.domain.Rule;
import com.iaf.console.drift.scorecards.domain.RulePack;
import com.iaf.console.drift.scorecards.domain.RulePacks;
import com.iaf.console.drift.scorecards.domain.RuleResult;
import com.iaf.console.drift.scorecards.domain.RuleStatus;
import com.iaf.console.drift.scorecards.domain.Verdict;
import com.iaf.console.drift.scorecards.ports.outbound.EvaluationStorePort;
import com.iaf.console.drift.scorecards.ports.outbound.PolicyEnginePort;
import com.iaf.console.drift.scorecards.ports.outbound.ScoreStorePort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Scores a resource against every rule pack whenever the drift service reads it or its desired
 * state changes, so scoring costs no read of its own. An unchanged input under the same packs
 * skips the engine; a changed verdict is stored conditionally on the evaluation's version, the
 * account's counters take the difference, and {@code EvaluationChanged} goes out. When the engine
 * cannot be asked at all, the stored evaluation stands until the next read.
 */
public final class ScorecardEvaluator implements ResourceListener {

    private static final Logger log = LoggerFactory.getLogger(ScorecardEvaluator.class);
    static final int ATTEMPTS = 3;

    private final RulePacks packs;
    private final PolicyEnginePort engine;
    private final EvaluationStorePort evaluations;
    private final ScoreStorePort scores;
    private final ScorecardEvents events;
    private final ScorecardMetrics metrics;
    private final Clock clock;

    public ScorecardEvaluator(RulePacks packs, PolicyEnginePort engine, EvaluationStorePort evaluations, ScoreStorePort scores, ScorecardEvents events,
            ScorecardMetrics metrics, Clock clock) {
        this.packs = packs;
        this.engine = engine;
        this.evaluations = evaluations;
        this.scores = scores;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** What scoring one resource did. */
    public enum Outcome { UNCHANGED, SKIPPED, CHANGED, REMOVED, ENGINE_DOWN }

    @Override
    public Mono<Void> observed(Watch watch, JsonNode observed) {
        return score(watch, observed, false).then();
    }

    @Override
    public Mono<Void> changed(Watch watch) {
        return score(watch, null, false).then();
    }

    @Override
    public Mono<Void> removed(String wfrn) {
        return Mono.defer(() -> evaluations.delete(wfrn))
                .flatMap(gone -> account(gone, null).thenReturn(Outcome.REMOVED))
                .then();
    }

    /**
     * Scores the resource: {@code observed} is a provider read (null keeps the last one);
     * {@code force} asks the engine even when nothing changed (after a pack changed).
     */
    public Mono<Outcome> score(Watch watch, JsonNode observed, boolean force) {
        if (watch.deleted()) {
            return removed(watch.wfrn()).thenReturn(Outcome.REMOVED);
        }
        return Mono.defer(() -> scoreOnce(watch, observed, force))
                .retryWhen(Retry.max(ATTEMPTS).filter(Stale.class::isInstance))
                .onErrorResume(Stale.class, e -> {
                    log.info("{}: the evaluation keeps changing; the next read scores it again", watch.wfrn());
                    return Mono.just(Outcome.SKIPPED);
                });
    }

    private Mono<Outcome> scoreOnce(Watch watch, JsonNode observed, boolean force) {
        return evaluations.findByWfrn(watch.wfrn()).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(found -> {
            ResourceEvaluation before = found.orElse(null);
            ObjectNode seen = observed != null && observed.isObject() ? (ObjectNode) observed : before == null ? null : before.observed();
            ObjectNode input = EvaluationInput.of(watch, seen);
            String digest = EvaluationInput.digest(input);
            if (!force && before != null && digest.equals(before.inputDigest()) && packs.digest().equals(before.packsDigest())) {
                metrics.evaluated("unchanged");
                return Mono.just(Outcome.UNCHANGED);
            }
            return judge(watch, input).flatMap(results -> {
                Instant now = clock.instant();
                List<RuleResult> stamped = results.stream().map(r -> {
                    RuleResult old = before == null ? null : before.result(r.key());
                    Instant since = old != null && old.status() == r.status() ? old.since() : now;
                    return new RuleResult(r.pack(), r.ruleId(), r.severity(), r.status(), r.message(), since);
                }).toList();
                boolean verdictsChanged = before == null || !before.sameResults(stamped);
                if (!verdictsChanged && digest.equals(before.inputDigest()) && packs.digest().equals(before.packsDigest())) {
                    // a forced pass that found nothing new
                    metrics.evaluated("unchanged");
                    return Mono.just(Outcome.UNCHANGED);
                }
                ResourceEvaluation next = new ResourceEvaluation(watch.wfrn(), watch.accountId(), watch.region(), watch.type(), watch.name(),
                        (before == null ? 0 : before.version()) + 1, packs.digest(), digest, seen, stamped,
                        verdictsChanged || before == null ? now : before.evaluatedAt());
                return evaluations.save(next).flatMap(saved -> {
                    if (!saved) {
                        return Mono.error(new Stale());
                    }
                    if (!verdictsChanged) {
                        metrics.evaluated("refreshed");
                        return Mono.just(Outcome.UNCHANGED);
                    }
                    metrics.evaluated("changed");
                    List<ResourceEvaluation.Change> changes = ResourceEvaluation.changes(before, next);
                    return scores.apply(next.accountId(), ResourceEvaluation.deltas(before, next))
                            .then(changes.isEmpty() ? Mono.empty() : events.evaluationChanged(next, changes))
                            .thenReturn(Outcome.CHANGED);
                });
            }).switchIfEmpty(Mono.just(Outcome.ENGINE_DOWN));
        });
    }

    /** Every rule that may apply to the type, pack by pack; empty when the engine cannot be asked. */
    private Mono<List<RuleResult>> judge(Watch watch, ObjectNode input) {
        return Flux.fromIterable(packs.packs())
                .concatMap(pack -> {
                    List<Rule> rules = pack.rulesFor(watch.type());
                    if (rules.isEmpty()) {
                        return Flux.empty();
                    }
                    return engine.evaluate(pack, rules, input).map(verdicts -> results(pack, rules, verdicts)).flatMapIterable(l -> l);
                })
                .collectList()
                .onErrorResume(e -> {
                    metrics.engineError(engine.name());
                    log.warn("{}: the policy engine ({}) could not be asked: {}", watch.wfrn(), engine.name(), e.toString());
                    return Mono.empty();
                });
    }

    private static List<RuleResult> results(RulePack pack, List<Rule> rules, Map<String, Verdict> verdicts) {
        List<RuleResult> out = new ArrayList<>();
        for (Rule rule : rules) {
            Verdict v = verdicts.get(rule.id());
            if (v == null) {
                out.add(new RuleResult(pack.name(), rule.id(), rule.severity(), RuleStatus.ERROR, "the policy engine gave no verdict", null));
            } else if (v.applies()) {
                out.add(new RuleResult(pack.name(), rule.id(), rule.severity(), v.passed() ? RuleStatus.PASS : RuleStatus.FAIL,
                        v.passed() ? null : v.message() != null ? v.message() : rule.message(), null));
            }
        }
        return out;
    }

    private Mono<Void> account(ResourceEvaluation gone, ResourceEvaluation now) {
        List<ResourceEvaluation.Change> changes = ResourceEvaluation.changes(gone, now);
        return scores.apply(gone.accountId(), ResourceEvaluation.deltas(gone, now))
                .then(Mono.defer(() -> {
                    metrics.evaluated("removed");
                    return changes.isEmpty() ? Mono.<Void>empty()
                            : events.evaluationChanged(new ResourceEvaluation(gone.wfrn(), gone.accountId(), gone.region(), gone.type(), gone.name(),
                                    gone.version() + 1, gone.packsDigest(), null, null, List.of(), clock.instant()), changes);
                }));
    }

    /** Another writer stored an evaluation of the resource since it was read. */
    static final class Stale extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stale() {
            super("the evaluation changed since it was read", null, false, false);
        }
    }
}

package com.iaf.console.drift.scorecards.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.drift.scorecards.domain.Counts;
import com.iaf.console.drift.scorecards.domain.CountDelta;
import com.iaf.console.drift.scorecards.domain.ResourceEvaluation;
import com.iaf.console.drift.scorecards.domain.RuleKey;
import com.iaf.console.drift.scorecards.domain.RulePacks;
import com.iaf.console.drift.scorecards.domain.ScoreSnapshot;
import com.iaf.console.drift.scorecards.ports.outbound.EvaluationStorePort;
import com.iaf.console.drift.scorecards.ports.outbound.ScoreStorePort;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The daily work: each account's counters are recounted from its evaluations (so a delta lost to a
 * crash between an evaluation's write and its counters' update lasts a day at most) and the day's
 * score is recorded as the trend's point, with {@code ScoreSnapshotted}. A tick snapshots only the
 * accounts without one for today, so it is safe on every replica and after a restart. Rescoring
 * walks the watch list and asks the engine again, for when a pack changed.
 */
public final class ScorecardSnapshots {

    /** A scorecard is of the whole account: its stores never filter on the scope's region. */
    static final String ANY_REGION = "global";

    private static final Logger log = LoggerFactory.getLogger(ScorecardSnapshots.class);
    private static final int PAGE = 200;

    private final RulePacks packs;
    private final EvaluationStorePort evaluations;
    private final ScoreStorePort scores;
    private final WatchStorePort watches;
    private final ScorecardEvaluator evaluator;
    private final ScorecardEvents events;
    private final ScorecardMetrics metrics;
    private final Clock clock;

    public ScorecardSnapshots(RulePacks packs, EvaluationStorePort evaluations, ScoreStorePort scores, WatchStorePort watches,
            ScorecardEvaluator evaluator, ScorecardEvents events, ScorecardMetrics metrics, Clock clock) {
        this.packs = packs;
        this.evaluations = evaluations;
        this.scores = scores;
        this.watches = watches;
        this.evaluator = evaluator;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** The accounts without today's snapshot get one; answers how many. */
    public Mono<Integer> tick() {
        LocalDate today = today();
        return accounts()
                .filterWhen(account -> scores.findLatestSnapshot(account).map(s -> s.day().isBefore(today)).defaultIfEmpty(true))
                .concatMap(account -> snapshot(account, today).onErrorResume(e -> {
                    metrics.snapshot("error");
                    log.warn("account {}: the scorecard snapshot failed: {}", account, e.toString());
                    return Mono.empty();
                }))
                .count()
                .map(Long::intValue);
    }

    /** Every account gets today's snapshot now, replacing one taken earlier today. */
    public Mono<Integer> snapshotAll() {
        LocalDate today = today();
        return accounts().concatMap(account -> snapshot(account, today)).count().map(Long::intValue);
    }

    /** Recounts the account's counters from its evaluations and records the day's score. */
    public Mono<ScoreSnapshot> snapshot(String accountId, LocalDate day) {
        AccountScope scope = AccountScope.of(accountId, ANY_REGION, ScorecardEvents.ACTOR);
        return evaluations.listAll(scope)
                .reduce(new TreeMap<RuleKey, Counts>(), (acc, e) -> {
                    for (CountDelta d : ResourceEvaluation.deltas(null, e)) {
                        acc.merge(d.key(), new Counts(d.passed(), d.failed()), Counts::plus);
                    }
                    return acc;
                })
                .flatMap(counts -> scores.replace(accountId, counts).thenReturn(counts))
                .flatMap(counts -> {
                    ScorecardQueries.Score s = ScorecardQueries.score(packs, counts);
                    Map<String, Integer> byPack = new LinkedHashMap<>();
                    s.packs().forEach(p -> byPack.put(p.name(), p.score()));
                    ScoreSnapshot snapshot = new ScoreSnapshot(accountId, day, s.score(), byPack, s.total().failed());
                    return scores.findLatestSnapshot(accountId).map(prev -> prev.day().isBefore(day) ? prev.score() : null)
                            .map(java.util.Optional::ofNullable).defaultIfEmpty(java.util.Optional.empty())
                            .flatMap(previous -> scores.saveSnapshot(snapshot).then(events.scoreSnapshotted(snapshot, previous.orElse(null))))
                            .doOnSuccess(v -> {
                                metrics.snapshot("ok");
                                log.info("account {}: compliance score {} on {}", accountId, snapshot.score(), day);
                            })
                            .thenReturn(snapshot);
                });
    }

    /** Scores every watched resource of an account (or of all) again; answers how many and how many changed. */
    public Mono<int[]> rescore(String accountId) {
        Flux<String> accounts = accountId != null ? Flux.just(accountId) : watches.listAccounts();
        return accounts.concatMap(this::watchesOf)
                .concatMap(w -> evaluator.score(w, null, true))
                .reduce(new int[2], (acc, outcome) -> {
                    acc[0]++;
                    if (outcome == ScorecardEvaluator.Outcome.CHANGED) {
                        acc[1]++;
                    }
                    return acc;
                });
    }

    private Flux<Watch> watchesOf(String accountId) {
        AccountScope scope = AccountScope.of(accountId, ANY_REGION, ScorecardEvents.ACTOR);
        return Mono.just(new Page<Watch>(List.of(), "")).expand(page -> page.next() == null ? Mono.empty()
                : watches.list(scope, null, PAGE, page.next().isEmpty() ? null : page.next()))
                .skip(1)
                .flatMapIterable(Page::items);
    }

    private Flux<String> accounts() {
        return Flux.merge(evaluations.listAccounts(), scores.listAccounts()).distinct();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}

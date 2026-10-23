package com.iaf.console.drift.scorecards.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Severity;
import com.iaf.console.drift.scorecards.domain.Counts;
import com.iaf.console.drift.scorecards.domain.RemediationInput;
import com.iaf.console.drift.scorecards.domain.RemediationSpec;
import com.iaf.console.drift.scorecards.domain.ResourceEvaluation;
import com.iaf.console.drift.scorecards.domain.Rule;
import com.iaf.console.drift.scorecards.domain.RuleKey;
import com.iaf.console.drift.scorecards.domain.RulePack;
import com.iaf.console.drift.scorecards.domain.RulePacks;
import com.iaf.console.drift.scorecards.domain.RuleStatus;
import com.iaf.console.drift.scorecards.domain.ScoreSnapshot;
import com.iaf.console.drift.scorecards.domain.Scores;
import com.iaf.console.drift.scorecards.domain.Views;
import com.iaf.console.drift.scorecards.ports.outbound.EvaluationStorePort;
import com.iaf.console.drift.scorecards.ports.outbound.ScoreStorePort;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The scorecard API's reads: the packs, an account's scorecard with its trend, the heat map, the
 * failing resources of a rule and one resource's evaluations. Scores are worked out from the
 * counters of the packs in force; counters of rules no longer in a pack are left out.
 */
public final class ScorecardQueries {

    public static final int MAX_HEATMAP_ACCOUNTS = 500;
    public static final int MAX_TREND_DAYS = 400;

    private final RulePacks packs;
    private final EvaluationStorePort evaluations;
    private final ScoreStorePort scores;
    private final Clock clock;

    public ScorecardQueries(RulePacks packs, EvaluationStorePort evaluations, ScoreStorePort scores, Clock clock) {
        this.packs = packs;
        this.evaluations = evaluations;
        this.scores = scores;
        this.clock = clock;
    }

    public List<Views.RulePackSummary> packs() {
        return packs.packs().stream().map(ScorecardQueries::summary).toList();
    }

    public Mono<Views.Scorecard> scorecard(AccountScope scope, int days) {
        if (days < 1 || days > MAX_TREND_DAYS) {
            throw new IllegalArgumentException("days is 1 to " + MAX_TREND_DAYS);
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from = today.minusDays(days - 1L);
        return scores.countsOf(scope).zipWith(scores.listSnapshots(scope, from).collectList()).map(found -> {
            Map<RuleKey, Counts> counts = found.getT1();
            Score live = score(packs, counts);
            List<Views.TrendPoint> trend = new ArrayList<>();
            for (ScoreSnapshot s : found.getT2()) {
                if (s.day().isBefore(today)) {
                    trend.add(new Views.TrendPoint(s.day(), s.score()));
                }
            }
            trend.add(new Views.TrendPoint(today, live.score()));
            Counts resources = counts.getOrDefault(RuleKey.RESOURCES, Counts.ZERO);
            return new Views.Scorecard(scope.accountId(), live.score(), live.total().passed(), live.total().failed(), resources.total(), resources.failed(),
                    live.bySeverity(), live.packs(), trend, null);
        });
    }

    /** One row per account asked, in the order asked; an account with nothing scored has null scores. */
    public Mono<Views.Heatmap> heatmap(Collection<String> accountIds) {
        if (accountIds.isEmpty() || accountIds.size() > MAX_HEATMAP_ACCOUNTS) {
            throw new IllegalArgumentException("accountIds names 1 to " + MAX_HEATMAP_ACCOUNTS + " accounts");
        }
        for (String a : accountIds) {
            if (!AccountScope.isAccountId(a)) {
                throw new IllegalArgumentException("accountIds are 12 digits each: " + a);
            }
        }
        List<Views.HeatmapPack> columns = packs.packs().stream().map(p -> new Views.HeatmapPack(p.name(), p.title())).toList();
        return scores.countsOfAccounts(accountIds).map(byAccount -> {
            List<Views.HeatmapRow> rows = new ArrayList<>();
            for (String account : accountIds) {
                Score s = score(packs, byAccount.getOrDefault(account, Map.of()));
                Map<String, Integer> byPack = new LinkedHashMap<>();
                s.packs().forEach(p -> byPack.put(p.name(), p.score()));
                rows.add(new Views.HeatmapRow(account, s.score(), s.total().failed(), byPack));
            }
            return new Views.Heatmap(columns, rows);
        });
    }

    public Mono<Page<Views.Evaluation>> failing(AccountScope scope, String pack, String ruleId, String type, int limit, Cursor after) {
        Rule rule = rule(pack, ruleId);
        return evaluations.listFailing(scope, rule.key(), type, limit, after);
    }

    public Mono<Views.ResourceCompliance> resource(AccountScope scope, String wfrn) {
        return evaluations.find(scope, wfrn).switchIfEmpty(Mono.error(() -> NotFoundException.of("evaluation of", wfrn))).map(this::compliance);
    }

    /** The rule of a pack in force; an unknown one is a 404. */
    public Rule rule(String pack, String ruleId) {
        return packs.rule(new RuleKey(pack, ruleId)).orElseThrow(() -> NotFoundException.of("rule", pack + "/" + ruleId));
    }

    Views.ResourceCompliance compliance(ResourceEvaluation e) {
        List<Views.RuleResultView> results = e.results().stream()
                .map(r -> {
                    Rule rule = packs.rule(r.key()).orElse(null);
                    return new Views.RuleResultView(r.pack(), r.ruleId(), rule == null ? r.ruleId() : rule.title(), r.severity(), r.status(), r.message(),
                            r.since(), rule != null && rule.remediation() != null);
                })
                .sorted(Comparator.comparing((Views.RuleResultView r) -> r.status() != RuleStatus.FAIL)
                        .thenComparing(r -> -r.severity().ordinal())
                        .thenComparing(Views.RuleResultView::pack).thenComparing(Views.RuleResultView::ruleId))
                .toList();
        return new Views.ResourceCompliance(e.wfrn(), e.accountId(), e.region(), e.type(), e.evaluatedAt(), e.passed(), e.failed(), results);
    }

    /** An account's score worked out from its counters, for the packs in force. */
    record Score(Integer score, Counts total, Map<String, Views.CountsView> bySeverity, List<Views.PackScore> packs) {
    }

    static Score score(RulePacks packs, Map<RuleKey, Counts> counts) {
        Map<Rule, Counts> all = new LinkedHashMap<>();
        Map<Severity, Counts> severities = new TreeMap<>();
        for (Severity s : Severity.values()) {
            severities.put(s, Counts.ZERO);
        }
        List<Views.PackScore> packScores = new ArrayList<>();
        Counts total = Counts.ZERO;
        for (RulePack pack : packs.packs()) {
            Map<Rule, Counts> ofPack = new LinkedHashMap<>();
            List<Views.RuleScore> rules = new ArrayList<>();
            Counts packTotal = Counts.ZERO;
            for (Rule rule : pack.rules()) {
                Counts c = counts.getOrDefault(rule.key(), Counts.ZERO);
                ofPack.put(rule, c);
                all.put(rule, c);
                packTotal = packTotal.plus(c);
                severities.merge(rule.severity(), c, Counts::plus);
                rules.add(new Views.RuleScore(rule.id(), rule.title(), rule.severity(), c.passed(), c.failed(), Scores.score(c), rule.remediation() != null));
            }
            total = total.plus(packTotal);
            packScores.add(new Views.PackScore(pack.name(), pack.title(), pack.version(), packTotal.passed(), packTotal.failed(), Scores.score(ofPack),
                    rules));
        }
        Map<String, Views.CountsView> bySeverity = new LinkedHashMap<>();
        for (Severity s : List.of(Severity.CRITICAL, Severity.WARNING, Severity.INFO)) {
            bySeverity.put(s.id(), Views.CountsView.of(severities.get(s)));
        }
        return new Score(Scores.score(all), total, bySeverity, packScores);
    }

    static Views.RulePackSummary summary(RulePack pack) {
        return new Views.RulePackSummary(pack.name(), pack.title(), pack.description(), pack.version(), pack.owner(),
                pack.rules().stream().map(r -> new Views.RuleSummary(r.id(), r.title(), r.description(), r.severity(), r.types().stream().sorted().toList(),
                        remediation(r.remediation()))).toList());
    }

    private static Views.RemediationSummary remediation(RemediationSpec spec) {
        if (spec == null) {
            return null;
        }
        List<Views.InputSummary> inputs = new ArrayList<>();
        for (RemediationInput i : spec.inputs()) {
            inputs.add(new Views.InputSummary(i.name(), i.title(), i.type(),
                    i.enumeration() == null ? null : i.enumeration().stream().map(ScorecardQueries::plain).toList(), plain(i.defaultValue()), i.required()));
        }
        return new Views.RemediationSummary(spec.title(), spec.kind(), spec.verb(), spec.workflow(), inputs);
    }

    private static Object plain(JsonNode node) {
        return node == null || node.isNull() ? null : Json.MAPPER.convertValue(node, Object.class);
    }
}

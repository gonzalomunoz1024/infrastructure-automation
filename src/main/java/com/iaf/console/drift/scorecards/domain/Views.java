package com.iaf.console.drift.scorecards.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.iaf.console.drift.drift.domain.Severity;

/** What the scorecard API answers, in the contract's shapes (contracts/openapi/console-drift-service.yaml). */
public final class Views {

    private Views() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RemediationSummary(String title, String kind, String verb, String workflow, List<InputSummary> inputs) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InputSummary(String name, String title, String type, @JsonProperty("enum") List<Object> enumeration,
            @JsonProperty("default") Object defaultValue, boolean required) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleSummary(String id, String title, String description, Severity severity, List<String> types, RemediationSummary remediation) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RulePackSummary(String name, String title, String description, int version, String owner, List<RuleSummary> rules) {
    }

    /** The scorecard's counts, whichever level they are of. */
    public record CountsView(long passed, long failed) {
        public static CountsView of(Counts c) {
            return new CountsView(c.passed(), c.failed());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RuleScore(String id, String title, Severity severity, long passed, long failed, Integer score, boolean remediable) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PackScore(String name, String title, int version, long passed, long failed, Integer score, List<RuleScore> rules) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TrendPoint(LocalDate day, Integer score) {
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record Scorecard(String accountId, @JsonInclude(JsonInclude.Include.ALWAYS) Integer score, long passed, long failed, long resources,
            long failingResources, Map<String, CountsView> bySeverity, List<PackScore> packs, List<TrendPoint> trend, Instant evaluatedAt) {
    }

    public record HeatmapPack(String name, String title) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HeatmapRow(String accountId, Integer score, long failed, Map<String, Integer> packs) {
    }

    public record Heatmap(List<HeatmapPack> packs, List<HeatmapRow> rows) {
    }

    /** One rule on one resource, as a list of failing resources shows it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Evaluation(String wfrn, String accountId, String region, String type, String name, String pack, String ruleId, Severity severity,
            RuleStatus status, String message, Instant since, Instant evaluatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleResultView(String pack, String ruleId, String title, Severity severity, RuleStatus status, String message, Instant since,
            boolean remediable) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceCompliance(String wfrn, String accountId, String region, String type, Instant evaluatedAt, long passed, long failed,
            List<RuleResultView> results) {
    }

    /** What a remediation did: the job it started, or the tags it set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Remediation(String wfrn, String pack, String ruleId, String kind, String jobId, String workflow, Map<String, String> tags) {
    }
}

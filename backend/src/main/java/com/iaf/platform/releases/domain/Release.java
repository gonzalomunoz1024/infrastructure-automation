package com.iaf.platform.releases.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One rollout of a workflow version, or of a provider version: the candidate ({@code to}) takes a
 * growing share of new jobs from the current version ({@code from}) stage by stage, judged by the
 * gates, until it is promoted or rolled back. A provider release names {@code provider} instead
 * of {@code workflow}; its gates judge the provider's calls. Shape as in docs/api.md.
 *
 * <p>{@code stageIndex} is the position in the plan; {@code stagePercent} is the share of new jobs
 * the candidate receives right now (0 while paused or rolled back, 100 once promoted).
 */
public record Release(
        String id,
        String workflow,
        String from,
        String to,
        String strategy,
        ReleaseStatus status,
        int stageIndex,
        List<Integer> stages,
        int stagePercent,
        Instant startedAt,
        Instant stageStartedAt,
        Instant endedAt,
        List<Gate> gates,
        Jobs jobs,
        List<HistoryEntry> history,
        String provider) {

    /** {@code passed} is null while the gate cannot be judged yet; it is always serialised so the UI sees the three states. */
    public record Gate(String id, String label, @JsonInclude(JsonInclude.Include.ALWAYS) Boolean passed, Double value, Double threshold, String detail) {
    }

    /** Finished jobs (provider calls, for a provider release) counted on each side in the current stage. */
    public record Jobs(int current, int candidate) {
        public static final Jobs NONE = new Jobs(0, 0);
    }

    public record HistoryEntry(Instant at, String event, String by) {
    }

    public static final String STRATEGY_CANARY = "canary";
    public static final String STRATEGY_ALL = "all";

    /** A workflow release. */
    public Release(String id, String workflow, String from, String to, String strategy, ReleaseStatus status, int stageIndex, List<Integer> stages,
            int stagePercent, Instant startedAt, Instant stageStartedAt, Instant endedAt, List<Gate> gates, Jobs jobs, List<HistoryEntry> history) {
        this(id, workflow, from, to, strategy, status, stageIndex, stages, stagePercent, startedAt, stageStartedAt, endedAt, gates, jobs, history, null);
    }

    /** A canary at its first stage. */
    public static Release canary(String id, String workflow, String from, String to, List<Integer> stages, Instant now, String by) {
        List<Integer> plan = List.copyOf(stages);
        int percent = plan.get(0);
        return new Release(id, workflow, from, to, STRATEGY_CANARY, ReleaseStatus.CANARY, 0, plan, percent, now, now, null,
                ReleaseGates.fresh(now), Jobs.NONE, List.of(new HistoryEntry(now, "canary started at " + percent + "%", by)));
    }

    /** A release that never routes a share: a first version, or a {@code rollout.strategy: all} publish. */
    public static Release promotedAtOnce(String id, String workflow, String from, String to, String strategy, Instant now, String event, String by) {
        return new Release(id, workflow, from, to, strategy == null ? STRATEGY_ALL : strategy, ReleaseStatus.PROMOTED, 0, List.of(100), 100,
                now, now, now, List.of(), Jobs.NONE, List.of(new HistoryEntry(now, event, by)));
    }

    /** The same new release, of a provider instead of a workflow: its gates count calls. */
    public Release forProvider(String name) {
        List<Gate> judged = status == ReleaseStatus.CANARY ? ReleaseGates.fresh(stageStartedAt, ReleaseGates.Unit.CALLS) : gates;
        return new Release(id, null, from, to, strategy, status, stageIndex, stages, stagePercent, startedAt, stageStartedAt, endedAt, judged, jobs,
                history, name);
    }

    /** What the gates count. */
    public ReleaseGates.Unit unit() {
        return provider == null ? ReleaseGates.Unit.JOBS : ReleaseGates.Unit.CALLS;
    }

    /** What the release rolls out: the provider's name for a provider release, else the workflow's. */
    public String subject() {
        return provider != null ? provider : workflow;
    }

    public boolean active() {
        return status.active();
    }

    public boolean lastStage() {
        return stageIndex >= stages.size() - 1;
    }

    /** The share the plan gives the current stage, whatever the status. */
    public int plannedPercent() {
        return stages.get(Math.min(stageIndex, stages.size() - 1));
    }

    /** The judgement of the current stage, without any transition. */
    public Release withEvaluation(List<Gate> judged, Jobs counted) {
        return new Release(id, workflow, from, to, strategy, status, stageIndex, stages, stagePercent, startedAt, stageStartedAt, endedAt,
                judged, counted, history, provider);
    }

    /** Next stage of the plan; the gates and counts start over. Only valid when {@link #lastStage()} is false. */
    public Release advanced(Instant now, String by) {
        int next = stageIndex + 1;
        int percent = stages.get(next);
        return new Release(id, workflow, from, to, strategy, ReleaseStatus.CANARY, next, stages, percent, startedAt, now, null,
                ReleaseGates.fresh(now, unit()), Jobs.NONE, plus(new HistoryEntry(now, "advanced to " + percent + "%", by)), provider);
    }

    public Release promoted(Instant now, String event, String by) {
        return new Release(id, workflow, from, to, strategy, ReleaseStatus.PROMOTED, stageIndex, stages, 100, startedAt, stageStartedAt, now,
                gates, jobs, plus(new HistoryEntry(now, event, by)), provider);
    }

    public Release rolledBack(Instant now, String event, String by) {
        return new Release(id, workflow, from, to, strategy, ReleaseStatus.ROLLED_BACK, stageIndex, stages, 0, startedAt, stageStartedAt, now,
                gates, jobs, plus(new HistoryEntry(now, event, by)), provider);
    }

    public Release paused(Instant now, String by) {
        return new Release(id, workflow, from, to, strategy, ReleaseStatus.PAUSED, stageIndex, stages, 0, startedAt, stageStartedAt, null,
                gates, jobs, plus(new HistoryEntry(now, "paused", by)), provider);
    }

    public Release resumed(Instant now, String by) {
        int percent = plannedPercent();
        return new Release(id, workflow, from, to, strategy, ReleaseStatus.CANARY, stageIndex, stages, percent, startedAt, stageStartedAt, null,
                gates, jobs, plus(new HistoryEntry(now, "resumed at " + percent + "%", by)), provider);
    }

    private List<HistoryEntry> plus(HistoryEntry entry) {
        List<HistoryEntry> out = new ArrayList<>(history == null ? List.of() : history);
        out.add(entry);
        return List.copyOf(out);
    }
}

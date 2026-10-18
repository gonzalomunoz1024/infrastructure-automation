package com.iaf.platform.releases.adapters.outbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.demo.DemoReleases;
import com.iaf.platform.common.demo.DemoReleases.Outcome;
import com.iaf.platform.common.demo.DemoReleases.Stage;
import com.iaf.platform.common.demo.DemoReleases.Story;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.domain.Release.HistoryEntry;
import com.iaf.platform.releases.domain.ReleaseGates;
import com.iaf.platform.releases.domain.ReleaseGates.Evaluation;
import com.iaf.platform.releases.domain.ReleaseStatus;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.runs.ports.inbound.RunsQueries.JobOutcome;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Demo releases ({@code platform.demo.seed-history}), following {@link DemoReleases}. They are
 * recorded once the demo jobs exist, and every gate is judged by {@link ReleaseGates} on the jobs
 * that ran in the release's last stage, so a release, its gates and the jobs list agree. The
 * running canary is judged again by the release handler from then on, like any other.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoReleaseSeed {

    private final ReleaseStore store;
    private final WorkflowQueries workflows;
    private final RunsQueries runs;
    private final PlatformProperties props;
    private final Clock clock;

    /** After the demo jobs ({@code DemoRunsSeeder} runs just before). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() {
        if (props.getDemo() == null || !props.getDemo().isSeedHistory()) {
            return;
        }
        int seeded = seed();
        log.info("seeded {} demo release(s)", seeded);
    }

    public int seed() {
        Instant now = clock.instant();
        int count = 0;
        for (Story s : DemoReleases.STORIES) {
            WorkflowRecord to = workflows.version(s.workflow(), s.to()).blockOptional(Duration.ofSeconds(10)).orElse(null);
            if (to == null || to.definition() == null || store.find(s.id()).isPresent()) {
                continue;
            }
            store.save(release(s, to.definition(), now));
            count++;
        }
        return count;
    }

    Release release(Story s, WorkflowDefinition def, Instant now) {
        Instant started = s.startedAt(now);
        if (!s.canary()) {
            return Release.promotedAtOnce(s.id(), s.workflow(), s.from(), s.to(), s.strategy(), started, "published to everyone", s.startedBy());
        }
        List<Integer> plan = def.rolloutOrDefault().stages() == null || def.rolloutOrDefault().stages().isEmpty()
                ? WorkflowDefinition.Rollout.CANARY.stages() : def.rolloutOrDefault().stages();
        Stage last = s.lastStage();
        Instant stageStarted = now.minus(last.ago());
        Instant end = s.active() ? now : s.endedAt(now);
        Evaluation judged = judge(s, stageStarted, end, now);
        int index = Math.max(0, plan.indexOf(last.percent()));
        List<HistoryEntry> history = new ArrayList<>();
        history.add(new HistoryEntry(started, "canary started at " + s.stages().get(0).percent() + "%", s.startedBy()));
        for (Stage stage : s.stages().subList(1, s.stages().size())) {
            history.add(new HistoryEntry(now.minus(stage.ago()), "advanced to " + stage.percent() + "%", Identity.SYSTEM.id()));
        }
        Release r = new Release(s.id(), s.workflow(), s.from(), s.to(), s.strategy(), ReleaseStatus.CANARY, index, plan, last.percent(),
                started, stageStarted, null, judged.gates(), judged.jobs(), List.copyOf(history));
        if (s.outcome() == Outcome.PROMOTED) {
            boolean byHand = s.endedBy() != null;
            return r.promoted(end, byHand ? "promoted" : "promoted: every gate passed at " + last.percent() + "%",
                    byHand ? s.endedBy() : Identity.SYSTEM.id());
        }
        if (s.outcome() == Outcome.ROLLED_BACK) {
            String reason = judged.failing().map(g -> g.id() + " gate failed (" + g.detail() + ")")
                    .orElse(s.failure() == null ? "rolled back" : "error-types gate failed (new on the candidate: " + s.failure().errorClass() + ")");
            return r.rolledBack(end, "rolled back: " + reason, s.endedBy() == null ? Identity.SYSTEM.id() : s.endedBy());
        }
        return r;
    }

    /** The gates over the jobs that started in the stage and finished by {@code end}, the way the release handler judges them. */
    private Evaluation judge(Story s, Instant stageStarted, Instant end, Instant now) {
        List<JobOutcome> candidate = within(runs.outcomes(s.workflow(), s.to(), stageStarted).block(Duration.ofSeconds(10)), end);
        List<JobOutcome> current = within(runs.outcomes(s.workflow(), s.from(), stageStarted).block(Duration.ofSeconds(10)), end);
        if (current.isEmpty()) {
            Instant lookback = s.startedAt(now).minus(ReleaseGates.BASELINE_LOOKBACK);
            current = within(runs.outcomes(s.workflow(), s.from(), lookback).block(Duration.ofSeconds(10)), end);
        }
        return ReleaseGates.evaluate(candidate, current, stageStarted, end);
    }

    private static List<JobOutcome> within(List<JobOutcome> outcomes, Instant end) {
        if (outcomes == null) {
            return List.of();
        }
        return outcomes.stream().filter(o -> o.startedAt() == null || o.startedAt().isBefore(end)).toList();
    }
}

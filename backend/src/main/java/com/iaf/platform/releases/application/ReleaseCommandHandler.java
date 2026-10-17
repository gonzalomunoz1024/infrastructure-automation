package com.iaf.platform.releases.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.domain.AccessRules;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.domain.ReleaseGates;
import com.iaf.platform.releases.domain.ReleaseGates.Evaluation;
import com.iaf.platform.releases.domain.ReleaseStatus;
import com.iaf.platform.releases.ports.inbound.ReleaseCommands;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.runs.ports.inbound.RunsQueries.JobOutcome;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Starts a release when a workflow version is published, judges the gates (every minute, and
 * whenever a job of the workflow finishes) and takes the manual decisions. Every transition is a
 * {@code release.*} event; the audit slice records them as decisions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseCommandHandler implements ReleaseCommands {

    /** When the current version has no job since the stage started (a 100% stage), its baseline comes from recent history. */
    static final Duration BASELINE_LOOKBACK = Duration.ofDays(7);

    private static final String AGGREGATE = "release";

    private final ReleaseStore store;
    private final WorkflowQueries workflows;
    private final RunsQueries runs;
    private final EventBus events;
    private final Clock clock;
    private final ObjectMapper mapper;

    @PostConstruct
    public void subscribe() {
        events.register(this::on);
    }

    void on(DomainEvent event) {
        switch (event.type()) {
            case "workflow.published" -> onPublished(event);
            case "job.finished" -> onJobFinished(event);
            default -> {
            }
        }
    }

    // ---- start -------------------------------------------------------------------------------

    private void onPublished(DomainEvent event) {
        JsonNode d = event.data();
        String name = text(d, "name");
        String version = text(d, "version");
        if (name == null || version == null) {
            log.warn("workflow.published without name/version: {}", d);
            return;
        }
        String by = Optional.ofNullable(text(d, "by")).orElse(event.actor());
        start(name, version, text(d, "previous"), text(d, "strategy"), by)
                .subscribe(r -> log.info("release {} for {} {} -> {}: {}", r.id(), name, r.from(), r.to(), r.status().json()),
                        e -> log.warn("could not start a release for {} {}: {}", name, version, e.toString()));
    }

    /** A promoted release for a first version or an {@code all} publish; otherwise a canary at the first stage. */
    Mono<Release> start(String workflow, String version, String previous, String strategy, String by) {
        Instant now = clock.instant();
        supersede(workflow, version, previous, now, by);
        if (previous == null || Release.STRATEGY_ALL.equals(strategy)) {
            String event = previous == null ? "first version takes all traffic" : "published to everyone";
            Release release = store.save(Release.promotedAtOnce(Ids.next("rel"), workflow, previous, version, strategy, now, event, by));
            events.publish("release.promoted", AGGREGATE, release.id(), by, versions(release));
            return Mono.just(release);
        }
        return stagesOf(workflow, version).map(stages -> {
            Release release = store.save(Release.canary(Ids.next("rel"), workflow, previous, version, stages, now, by));
            events.publish("release.started", AGGREGATE, release.id(), by, versions(release).put("stagePercent", release.stagePercent()));
            return release;
        });
    }

    /** A new publish ends whatever release was still running for the workflow. */
    private void supersede(String workflow, String version, String previous, Instant now, String by) {
        for (Release old : store.list(workflow)) {
            if (!old.active()) {
                continue;
            }
            if (old.to().equals(previous)) {
                Release done = store.save(old.promoted(now, "superseded: " + version + " was published on top of " + old.to(), by));
                events.publish("release.promoted", AGGREGATE, done.id(), by, versions(done));
            } else {
                String reason = "superseded by " + version;
                Release done = store.save(old.rolledBack(now, reason, by));
                events.publish("release.rolled-back", AGGREGATE, done.id(), by, versions(done).put("reason", reason));
            }
        }
    }

    private Mono<List<Integer>> stagesOf(String workflow, String version) {
        List<Integer> fallback = WorkflowDefinition.Rollout.CANARY.stages();
        return workflows.version(workflow, version)
                .map(ReleaseCommandHandler::stages)
                .filter(ReleaseCommandHandler::validStages)
                .defaultIfEmpty(fallback)
                .onErrorResume(e -> {
                    log.warn("cannot read the rollout stages of {} {}: {}", workflow, version, e.toString());
                    return Mono.just(fallback);
                });
    }

    private static List<Integer> stages(WorkflowRecord record) {
        if (record.definition() == null || record.definition().rolloutOrDefault().stages() == null) {
            return List.of();
        }
        return record.definition().rolloutOrDefault().stages();
    }

    private static boolean validStages(List<Integer> stages) {
        if (stages.isEmpty()) {
            return false;
        }
        int last = 0;
        for (Integer s : stages) {
            if (s == null || s <= last || s > 100) {
                return false;
            }
            last = s;
        }
        return true;
    }

    // ---- judge -------------------------------------------------------------------------------

    private void onJobFinished(DomainEvent event) {
        String workflow = text(event.data(), "workflow");
        if (workflow == null) {
            return;
        }
        store.active(workflow).filter(r -> r.status() == ReleaseStatus.CANARY).ifPresent(r -> judge(r, Identity.SYSTEM.id())
                .subscribe(j -> log.debug("release {} judged after {}: {}", j.id(), text(event.data(), "jobId"), j.status().json()),
                        e -> log.warn("release {} could not be judged: {}", r.id(), e.toString())));
    }

    /** Every active canary, once a minute. Runs on the scheduler thread, so waiting here is fine. */
    @Scheduled(fixedDelay = 60000)
    public void evaluateActiveCanaries() {
        Flux.fromIterable(store.active())
                .filter(r -> r.status() == ReleaseStatus.CANARY)
                .concatMap(r -> judge(r, Identity.SYSTEM.id())
                        .onErrorResume(e -> {
                            log.warn("release {} could not be judged: {}", r.id(), e.toString());
                            return Mono.empty();
                        }))
                .blockLast();
    }

    /**
     * Compute the gates of a release and apply the verdict. A paused release only gets its gates
     * refreshed; a finished one is a conflict.
     */
    Mono<Release> judge(Release release, String by) {
        if (!release.active()) {
            return Mono.error(new ConflictException("release " + release.id() + " is " + release.status().json() + "; nothing to evaluate"));
        }
        Instant now = clock.instant();
        return Mono.zip(candidateOutcomes(release), baselineOutcomes(release)).map(t -> {
            Evaluation evaluation = ReleaseGates.evaluate(t.getT1(), t.getT2(), release.stageStartedAt(), now);
            Release judged = release.withEvaluation(evaluation.gates(), evaluation.jobs());
            if (judged.status() == ReleaseStatus.PAUSED) {
                return store.save(judged);
            }
            return switch (evaluation.verdict()) {
                case WAIT -> store.save(judged);
                case ADVANCE -> advance(judged, now, by);
                case ROLLBACK -> rollBack(judged, now, by, evaluation.failing()
                        .map(g -> g.id() + " gate failed (" + g.detail() + ")").orElse("a gate failed"));
            };
        });
    }

    private Mono<List<JobOutcome>> candidateOutcomes(Release r) {
        return runs.outcomes(r.workflow(), r.to(), r.stageStartedAt()).defaultIfEmpty(List.of());
    }

    private Mono<List<JobOutcome>> baselineOutcomes(Release r) {
        if (r.from() == null) {
            return Mono.just(List.of());
        }
        return runs.outcomes(r.workflow(), r.from(), r.stageStartedAt()).defaultIfEmpty(List.of())
                .flatMap(since -> since.isEmpty()
                        ? runs.outcomes(r.workflow(), r.from(), r.startedAt().minus(BASELINE_LOOKBACK)).defaultIfEmpty(List.of())
                        : Mono.just(since));
    }

    private Release advance(Release judged, Instant now, String by) {
        if (judged.lastStage()) {
            Release done = store.save(judged.promoted(now, "promoted: every gate passed at " + judged.stagePercent() + "%", by));
            events.publish("release.promoted", AGGREGATE, done.id(), by, versions(done));
            return done;
        }
        Release next = store.save(judged.advanced(now, by));
        events.publish("release.stage", AGGREGATE, next.id(), by,
                versions(next).put("stageIndex", next.stageIndex()).put("stagePercent", next.stagePercent()));
        return next;
    }

    private Release rollBack(Release judged, Instant now, String by, String reason) {
        Release done = store.save(judged.rolledBack(now, "rolled back: " + reason, by));
        events.publish("release.rolled-back", AGGREGATE, done.id(), by, versions(done).put("reason", reason));
        return done;
    }

    // ---- commands ----------------------------------------------------------------------------

    @Override
    public Mono<CommandAccepted> evaluate(String releaseId) {
        return authorized(releaseId).flatMap(a -> judge(a.release(), a.who().id())).map(ReleaseCommandHandler::accepted);
    }

    @Override
    public Mono<CommandAccepted> promote(String releaseId) {
        return authorized(releaseId).map(a -> {
            Release r = requireActive(a.release(), "promote");
            Release done = store.save(r.promoted(clock.instant(), "promoted", a.who().id()));
            events.publish("release.promoted", AGGREGATE, done.id(), a.who().id(), versions(done));
            return accepted(done);
        });
    }

    @Override
    public Mono<CommandAccepted> pause(String releaseId) {
        return authorized(releaseId).map(a -> {
            Release r = a.release();
            if (r.status() != ReleaseStatus.CANARY) {
                throw new ConflictException("release " + r.id() + " is " + r.status().json() + "; only a canary can be paused");
            }
            Release done = store.save(r.paused(clock.instant(), a.who().id()));
            events.publish("release.paused", AGGREGATE, done.id(), a.who().id(), versions(done).put("stagePercent", 0));
            return accepted(done);
        });
    }

    @Override
    public Mono<CommandAccepted> resume(String releaseId) {
        return authorized(releaseId).map(a -> {
            Release r = a.release();
            if (r.status() != ReleaseStatus.PAUSED) {
                throw new ConflictException("release " + r.id() + " is " + r.status().json() + "; only a paused release can be resumed");
            }
            Release done = store.save(r.resumed(clock.instant(), a.who().id()));
            events.publish("release.resumed", AGGREGATE, done.id(), a.who().id(), versions(done).put("stagePercent", done.stagePercent()));
            return accepted(done);
        });
    }

    @Override
    public Mono<CommandAccepted> rollback(String releaseId) {
        return authorized(releaseId).map(a -> {
            Release r = requireActive(a.release(), "roll back");
            String reason = "rolled back by " + a.who().name();
            Release done = store.save(r.rolledBack(clock.instant(), "rolled back", a.who().id()));
            events.publish("release.rolled-back", AGGREGATE, done.id(), a.who().id(), versions(done).put("reason", reason));
            return accepted(done);
        });
    }

    private static Release requireActive(Release r, String what) {
        if (!r.active()) {
            throw new ConflictException("release " + r.id() + " is " + r.status().json() + "; cannot " + what + " it");
        }
        return r;
    }

    private record Authorized(Identity who, Release release) {
    }

    /** The release, once the caller is allowed to change the workflow it belongs to. */
    private Mono<Authorized> authorized(String releaseId) {
        return CurrentIdentity.current().flatMap(who -> {
            Release release = store.find(releaseId).orElseThrow(() -> new NotFoundException("release", releaseId));
            return ownerOf(release).map(Optional::of).defaultIfEmpty(Optional.empty()).map(owner -> {
                AccessRules.requireEdit(who, owner.orElse(null), "release " + releaseId + " of " + release.workflow());
                return new Authorized(who, release);
            });
        });
    }

    private Mono<String> ownerOf(Release release) {
        return workflows.version(release.workflow(), release.to())
                .switchIfEmpty(Mono.defer(() -> workflows.published(release.workflow())))
                .mapNotNull(r -> r.definition() == null || r.definition().metadata() == null ? null : r.definition().metadata().owner());
    }

    private static CommandAccepted accepted(Release r) {
        return CommandAccepted.of(r.id(), r.status().json(), Map.of("release", "/api/v1/releases/" + r.id()));
    }

    private ObjectNode versions(Release r) {
        return mapper.createObjectNode()
                .put("releaseId", r.id())
                .put("workflow", r.workflow())
                .put("from", r.from())
                .put("to", r.to());
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}

package com.iaf.platform.releases.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.domain.AccessRules;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
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
 *
 * <p>A provider version published with the {@code canary} strategy gets a provider release: its
 * gates judge the provider's calls ({@link TelemetryPort} samples of each version, sandbox calls
 * left out), every minute; the providers slice makes the candidate current once it is promoted.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ReleaseCommandHandler implements ReleaseCommands {

    private static final String AGGREGATE = "release";

    private final ReleaseStore store;
    private final WorkflowQueries workflows;
    private final RunsQueries runs;
    private final EventBus events;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final ProviderQueries providers;
    private final TelemetryPort telemetry;

    @PostConstruct
    public void subscribe() {
        events.register(this::on);
    }

    void on(DomainEvent event) {
        switch (event.type()) {
            case "workflow.published" -> onPublished(event);
            case "provider.published" -> onProviderPublished(event);
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

    private void onProviderPublished(DomainEvent event) {
        JsonNode d = event.data();
        String name = text(d, "name");
        String version = text(d, "version");
        if (name == null || version == null) {
            log.warn("provider.published without name/version: {}", d);
            return;
        }
        String by = Optional.ofNullable(text(d, "by")).orElse(event.actor());
        Release r = startProvider(name, version, text(d, "previous"), text(d, "strategy"), by);
        log.info("release {} for provider {} {} -> {}: {}", r.id(), name, r.from(), r.to(), r.status().json());
    }

    /**
     * The release of a provider version: promoted at once for a first version or an {@code all}
     * publish, otherwise a canary over the calls with the default stages. {@code previous} is the
     * version it rolls out against: the current one, or the candidate of a canary still running.
     */
    Release startProvider(String provider, String version, String previous, String strategy, String by) {
        Instant now = clock.instant();
        supersedeProvider(provider, version, previous, now, by);
        if (previous == null || Release.STRATEGY_ALL.equals(strategy)) {
            String event = previous == null ? "first version takes every call" : "published to every call";
            Release release = store.save(Release.promotedAtOnce(Ids.next("rel"), null, previous, version, strategy, now, event, by).forProvider(provider));
            events.publish("release.promoted", AGGREGATE, release.id(), by, versions(release));
            return release;
        }
        Release release = store.save(Release.canary(Ids.next("rel"), null, previous, version, WorkflowDefinition.Rollout.CANARY.stages(), now, by)
                .forProvider(provider));
        events.publish("release.started", AGGREGATE, release.id(), by, versions(release).put("stagePercent", release.stagePercent()));
        return release;
    }

    /** A new provider version ends the release still running: promoted when it was built on the candidate, else rolled back. */
    private void supersedeProvider(String provider, String version, String previous, Instant now, String by) {
        for (Release old : store.listProvider(provider)) {
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
            Evaluation evaluation = ReleaseGates.evaluate(t.getT1(), t.getT2(), release.stageStartedAt(), now, release.unit());
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
        if (r.provider() != null) {
            return calls(r.provider(), r.stageStartedAt(), s -> r.to().equals(s.providerVersion()));
        }
        return runs.outcomes(r.workflow(), r.to(), r.stageStartedAt()).defaultIfEmpty(List.of());
    }

    private Mono<List<JobOutcome>> baselineOutcomes(Release r) {
        if (r.from() == null) {
            return Mono.just(List.of());
        }
        if (r.provider() != null) {
            // before the release started every call used the current version, also those recorded without one
            return calls(r.provider(), r.stageStartedAt(), s -> r.from().equals(s.providerVersion()))
                    .flatMap(since -> since.isEmpty()
                            ? calls(r.provider(), r.startedAt().minus(ReleaseGates.BASELINE_LOOKBACK), s -> r.from().equals(s.providerVersion())
                                    || s.providerVersion() == null && s.at().isBefore(r.startedAt()))
                            : Mono.just(since));
        }
        return runs.outcomes(r.workflow(), r.from(), r.stageStartedAt()).defaultIfEmpty(List.of())
                .flatMap(since -> since.isEmpty()
                        ? runs.outcomes(r.workflow(), r.from(), r.startedAt().minus(ReleaseGates.BASELINE_LOOKBACK)).defaultIfEmpty(List.of())
                        : Mono.just(since));
    }

    /**
     * The calls of a provider since an instant that {@code version} picks, as outcomes the gates
     * judge: one per call. Sandbox calls ({@code dev}) always use the candidate, so they compare
     * nothing and are left out.
     */
    private Mono<List<JobOutcome>> calls(String provider, Instant since, Predicate<CallSample> version) {
        return Mono.fromCallable(() -> telemetry.samples(provider, since).stream()
                .filter(s -> s.at() != null && !ReleaseQueryHandler.SANDBOX.equals(s.environment()))
                .filter(version)
                .map(s -> new JobOutcome(null, null, s.providerVersion(), s.ok() ? "succeeded" : "failed", s.at(), s.at(), s.durationMs(),
                        s.ok() ? null : s.errorClass(), null, null))
                .toList());
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

    /** The team owning a release's workflow or provider: {@code exists} is false once it is gone, {@code team} null when it names none. */
    private record Owner(boolean exists, String team) {
    }

    /**
     * The release, once the caller is allowed to change the workflow or provider it belongs to.
     * Without an owner (the workflow or provider is gone, or names none) only an admin may.
     */
    private Mono<Authorized> authorized(String releaseId) {
        return CurrentIdentity.current().flatMap(who -> {
            Release release = store.find(releaseId).orElseThrow(() -> new NotFoundException("release", releaseId));
            String what = "release " + releaseId + " of " + release.subject();
            return ownerOf(release).map(owner -> {
                if (owner.team() != null && !owner.team().isBlank()) {
                    AccessRules.requireEdit(who, owner.team(), what);
                } else if (!who.admin()) {
                    String kind = release.provider() != null ? "provider" : "workflow";
                    throw new ForbiddenException(who.name() + " may not change " + what + ": its " + kind
                            + (owner.exists() ? " names no owner" : " no longer exists") + ", so only an admin may");
                }
                return new Authorized(who, release);
            });
        });
    }

    private Mono<Owner> ownerOf(Release release) {
        if (release.provider() != null) {
            return providers.provider(release.provider())
                    .map(def -> new Owner(true, def.metadata() == null ? null : def.owner()))
                    .defaultIfEmpty(new Owner(false, null));
        }
        return workflows.version(release.workflow(), release.to())
                .switchIfEmpty(Mono.defer(() -> workflows.published(release.workflow())))
                .map(r -> new Owner(true, r.definition() == null || r.definition().metadata() == null ? null : r.definition().metadata().owner()))
                .defaultIfEmpty(new Owner(false, null));
    }

    private static CommandAccepted accepted(Release r) {
        return CommandAccepted.of(r.id(), r.status().json(), Map.of("release", "/api/v1/releases/" + r.id()));
    }

    private ObjectNode versions(Release r) {
        ObjectNode data = mapper.createObjectNode().put("releaseId", r.id());
        if (r.provider() != null) {
            data.put("provider", r.provider());
        } else {
            data.put("workflow", r.workflow());
        }
        return data.put("from", r.from()).put("to", r.to());
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}

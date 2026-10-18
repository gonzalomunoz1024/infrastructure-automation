package com.iaf.platform.releases.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.adapters.telemetry.InMemoryTelemetry;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.releases.adapters.outbound.InMemoryReleaseStore;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.domain.ReleaseStatus;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.runs.ports.inbound.RunsQueries.JobOutcome;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReleaseCommandHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Instant THREE_HOURS_AGO = NOW.minus(Duration.ofHours(3));
    static final List<Integer> STAGES = List.of(10, 50, 100);
    static final Identity ANA = new Identity("ana", "Ana", "ana@example.com", List.of("team-data"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity MATEO = new Identity("mateo", "Mateo", "mateo@example.com", List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity GONZALO = new Identity("gonzalo", "Gonzalo", "gonzalo@example.com", List.of("team-compute", "team-platform"), List.of(), true, Identity.Kind.USER);

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final InMemoryReleaseStore store = new InMemoryReleaseStore();
    final WorkflowQueries workflows = mock(WorkflowQueries.class);
    final RunsQueries runs = mock(RunsQueries.class);
    final EventBus events = new EventBus(clock);
    final List<DomainEvent> published = new ArrayList<>();
    final ObjectMapper mapper = new ObjectMapper();
    final ProviderQueries providers = mock(ProviderQueries.class);
    final InMemoryTelemetry telemetry = new InMemoryTelemetry(new SimpleMeterRegistry(), clock);
    final ReleaseCommandHandler handler = new ReleaseCommandHandler(store, workflows, runs, events, clock, mapper, providers, telemetry);

    @BeforeEach
    void wire() {
        handler.subscribe();
        events.register(published::add);
        when(workflows.version(anyString(), anyString())).thenReturn(Mono.just(record("vm-restart", "1.2.0", "team-compute", STAGES)));
        when(workflows.published(anyString())).thenReturn(Mono.empty());
    }

    // ---- starting ----------------------------------------------------------------------------

    @Test
    void startsACanaryWhenAPreviousVersionExists() {
        when(workflows.version("vm-restart", "1.2.0")).thenReturn(Mono.just(record("vm-restart", "1.2.0", "team-compute", List.of(5, 50, 100))));

        publishWorkflow("vm-restart", "1.2.0", "1.1.0", "canary");

        Release r = store.active("vm-restart").orElseThrow();
        assertThat(r.status()).isEqualTo(ReleaseStatus.CANARY);
        assertThat(r.from()).isEqualTo("1.1.0");
        assertThat(r.to()).isEqualTo("1.2.0");
        assertThat(r.strategy()).isEqualTo("canary");
        assertThat(r.stages()).containsExactly(5, 50, 100);
        assertThat(r.stageIndex()).isZero();
        assertThat(r.stagePercent()).isEqualTo(5);
        assertThat(r.startedAt()).isEqualTo(NOW);
        assertThat(r.stageStartedAt()).isEqualTo(NOW);
        assertThat(r.endedAt()).isNull();
        assertThat(r.gates()).hasSize(4).allMatch(g -> g.passed() == null);
        assertThat(r.history()).hasSize(1);
        assertThat(r.history().get(0).event()).isEqualTo("canary started at 5%");
        assertThat(r.history().get(0).by()).isEqualTo("ana");
        DomainEvent started = only("release.started");
        assertThat(started.aggregateId()).isEqualTo(r.id());
        assertThat(started.data().get("releaseId").asText()).isEqualTo(r.id());
        assertThat(started.data().get("workflow").asText()).isEqualTo("vm-restart");
        assertThat(started.data().get("from").asText()).isEqualTo("1.1.0");
        assertThat(started.data().get("to").asText()).isEqualTo("1.2.0");
        assertThat(started.data().get("stagePercent").asInt()).isEqualTo(5);
    }

    @Test
    void aFirstVersionTakesAllTrafficAtOnce() {
        publishWorkflow("vm-restart", "1.0.0", null, "canary");

        Release r = store.list("vm-restart").get(0);
        assertThat(r.status()).isEqualTo(ReleaseStatus.PROMOTED);
        assertThat(r.from()).isNull();
        assertThat(r.to()).isEqualTo("1.0.0");
        assertThat(r.stagePercent()).isEqualTo(100);
        assertThat(r.endedAt()).isEqualTo(NOW);
        assertThat(r.history()).extracting(Release.HistoryEntry::event).containsExactly("first version takes all traffic");
        assertThat(store.active("vm-restart")).isEmpty();
        assertThat(only("release.promoted").data().get("to").asText()).isEqualTo("1.0.0");
        assertThat(published).noneMatch(e -> e.type().equals("release.started"));
    }

    @Test
    void anAllStrategyPublishesToEveryoneAtOnce() {
        publishWorkflow("request-storage", "2.0.0", "1.4.0", "all");

        Release r = store.list("request-storage").get(0);
        assertThat(r.status()).isEqualTo(ReleaseStatus.PROMOTED);
        assertThat(r.strategy()).isEqualTo("all");
        assertThat(r.from()).isEqualTo("1.4.0");
        assertThat(r.history()).extracting(Release.HistoryEntry::event).containsExactly("published to everyone");
        assertThat(only("release.promoted").data().get("from").asText()).isEqualTo("1.4.0");
    }

    @Test
    void fallsBackToTheDefaultStagesWhenTheVersionCannotBeRead() {
        when(workflows.version("vm-restart", "1.2.0")).thenReturn(Mono.empty());

        publishWorkflow("vm-restart", "1.2.0", "1.1.0", "canary");

        assertThat(store.active("vm-restart").orElseThrow().stages()).containsExactly(10, 50, 100);
    }

    @Test
    void aNewPublishSupersedesTheRunningCanary() {
        Release old = store.save(Release.canary("rel_old", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));

        publishWorkflow("vm-restart", "1.3.0", "1.2.0", "canary");

        assertThat(store.find(old.id()).orElseThrow().status()).isEqualTo(ReleaseStatus.PROMOTED);
        assertThat(store.find(old.id()).orElseThrow().history().get(1).event()).startsWith("superseded");
        Release fresh = store.active("vm-restart").orElseThrow();
        assertThat(fresh.id()).isNotEqualTo(old.id());
        assertThat(fresh.from()).isEqualTo("1.2.0");
        assertThat(fresh.to()).isEqualTo("1.3.0");
        assertThat(only("release.promoted").aggregateId()).isEqualTo(old.id());
    }

    // ---- judging -----------------------------------------------------------------------------

    @Test
    void advancesToTheNextStageWhenEveryGatePasses() {
        Release canary = store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));
        when(runs.outcomes("vm-restart", "1.2.0", THREE_HOURS_AGO)).thenReturn(Mono.just(many(25, () -> ok("1.2.0", 1000))));
        when(runs.outcomes("vm-restart", "1.1.0", THREE_HOURS_AGO)).thenReturn(Mono.just(many(30, () -> ok("1.1.0", 1000))));

        StepVerifier.create(handler.judge(canary, "platform"))
                .assertNext(r -> {
                    assertThat(r.status()).isEqualTo(ReleaseStatus.CANARY);
                    assertThat(r.stageIndex()).isEqualTo(1);
                    assertThat(r.stagePercent()).isEqualTo(50);
                    assertThat(r.stageStartedAt()).isEqualTo(NOW);
                    assertThat(r.history()).extracting(Release.HistoryEntry::event).containsExactly("canary started at 10%", "advanced to 50%");
                    assertThat(r.gates()).allMatch(g -> g.passed() == null);
                    assertThat(r.jobs()).isEqualTo(Release.Jobs.NONE);
                })
                .verifyComplete();
        assertThat(store.find("rel_1").orElseThrow().stageIndex()).isEqualTo(1);
        DomainEvent stage = only("release.stage");
        assertThat(stage.data().get("releaseId").asText()).isEqualTo("rel_1");
        assertThat(stage.data().get("stagePercent").asInt()).isEqualTo(50);
    }

    @Test
    void promotesPastTheLastStageUsingRecentHistoryAsTheBaseline() {
        Release last = new Release("rel_1", "vm-restart", "1.1.0", "1.2.0", "canary", ReleaseStatus.CANARY, 2, STAGES, 100,
                NOW.minus(Duration.ofHours(9)), THREE_HOURS_AGO, null, List.of(), Release.Jobs.NONE, List.of());
        store.save(last);
        when(runs.outcomes("vm-restart", "1.2.0", THREE_HOURS_AGO)).thenReturn(Mono.just(many(25, () -> ok("1.2.0", 1000))));
        when(runs.outcomes("vm-restart", "1.1.0", THREE_HOURS_AGO)).thenReturn(Mono.just(List.of()));
        when(runs.outcomes("vm-restart", "1.1.0", last.startedAt().minus(com.iaf.platform.releases.domain.ReleaseGates.BASELINE_LOOKBACK)))
                .thenReturn(Mono.just(many(30, () -> ok("1.1.0", 1000))));

        StepVerifier.create(handler.judge(last, "platform"))
                .assertNext(r -> {
                    assertThat(r.status()).isEqualTo(ReleaseStatus.PROMOTED);
                    assertThat(r.stagePercent()).isEqualTo(100);
                    assertThat(r.endedAt()).isEqualTo(NOW);
                    assertThat(r.history().get(0).event()).isEqualTo("promoted: every gate passed at 100%");
                    assertThat(r.jobs()).isEqualTo(new Release.Jobs(30, 25));
                })
                .verifyComplete();
        assertThat(only("release.promoted").data().get("to").asText()).isEqualTo("1.2.0");
    }

    @Test
    void rollsBackWhenAGateFailsAndSaysWhich() {
        Release canary = store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));
        List<JobOutcome> candidate = new ArrayList<>(many(49, () -> ok("1.2.0", 1000)));
        candidate.add(failed("1.2.0", "ReplicaSetNotReady"));
        List<JobOutcome> current = new ArrayList<>(many(40, () -> ok("1.1.0", 1000)));
        current.add(failed("1.1.0", "Timeout"));
        when(runs.outcomes("vm-restart", "1.2.0", THREE_HOURS_AGO)).thenReturn(Mono.just(candidate));
        when(runs.outcomes("vm-restart", "1.1.0", THREE_HOURS_AGO)).thenReturn(Mono.just(current));

        StepVerifier.create(handler.judge(canary, "platform"))
                .assertNext(r -> {
                    assertThat(r.status()).isEqualTo(ReleaseStatus.ROLLED_BACK);
                    assertThat(r.stagePercent()).isZero();
                    assertThat(r.endedAt()).isEqualTo(NOW);
                    assertThat(r.history().get(1).event()).isEqualTo("rolled back: error-types gate failed (new on the candidate: ReplicaSetNotReady)");
                    assertThat(r.history().get(1).by()).isEqualTo("platform");
                    assertThat(r.gates().get(2).passed()).isFalse();
                })
                .verifyComplete();
        DomainEvent rolledBack = only("release.rolled-back");
        assertThat(rolledBack.data().get("reason").asText()).startsWith("error-types gate failed");
        assertThat(rolledBack.data().get("from").asText()).isEqualTo("1.1.0");
    }

    @Test
    void keepsWaitingWhileTheSampleBuildsUp() {
        Release canary = store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));
        when(runs.outcomes("vm-restart", "1.2.0", THREE_HOURS_AGO)).thenReturn(Mono.just(many(5, () -> ok("1.2.0", 1000))));
        when(runs.outcomes("vm-restart", "1.1.0", THREE_HOURS_AGO)).thenReturn(Mono.just(many(30, () -> ok("1.1.0", 1000))));

        StepVerifier.create(handler.judge(canary, "platform"))
                .assertNext(r -> {
                    assertThat(r.status()).isEqualTo(ReleaseStatus.CANARY);
                    assertThat(r.stageIndex()).isZero();
                    assertThat(r.jobs()).isEqualTo(new Release.Jobs(30, 5));
                    assertThat(r.gates().get(3).detail()).isEqualTo("5 of 20 jobs, 3h in the stage");
                    assertThat(r.history()).hasSize(1);
                })
                .verifyComplete();
        assertThat(published).noneMatch(e -> e.type().startsWith("release."));
    }

    @Test
    void refreshesTheGatesOfAPausedReleaseWithoutMovingIt() {
        Release paused = store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana").paused(NOW, "ana"));
        when(runs.outcomes(eq("vm-restart"), eq("1.2.0"), any())).thenReturn(Mono.just(many(25, () -> ok("1.2.0", 1000))));
        when(runs.outcomes(eq("vm-restart"), eq("1.1.0"), any())).thenReturn(Mono.just(many(30, () -> ok("1.1.0", 1000))));

        StepVerifier.create(handler.judge(paused, "platform"))
                .assertNext(r -> {
                    assertThat(r.status()).isEqualTo(ReleaseStatus.PAUSED);
                    assertThat(r.stageIndex()).isZero();
                    assertThat(r.jobs().candidate()).isEqualTo(25);
                    assertThat(r.gates()).allMatch(g -> Boolean.TRUE.equals(g.passed()));
                })
                .verifyComplete();
        assertThat(published).noneMatch(e -> e.type().startsWith("release."));
    }

    @Test
    void judgingAFinishedReleaseIsAConflict() {
        Release done = Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana").promoted(NOW, "promoted", "ana");

        StepVerifier.create(handler.judge(done, "platform")).expectError(ConflictException.class).verify();
    }

    @Test
    void aFinishedJobOfTheWorkflowReEvaluatesItsCanary() {
        store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));
        when(runs.outcomes("vm-restart", "1.2.0", THREE_HOURS_AGO)).thenReturn(Mono.just(many(25, () -> ok("1.2.0", 1000))));
        when(runs.outcomes("vm-restart", "1.1.0", THREE_HOURS_AGO)).thenReturn(Mono.just(many(30, () -> ok("1.1.0", 1000))));

        events.publish("job.finished", "job", "job_1", "platform", mapper.createObjectNode()
                .put("jobId", "job_1").put("workflow", "vm-restart").put("version", "1.2.0").put("status", "succeeded"));

        assertThat(store.find("rel_1").orElseThrow().stageIndex()).isEqualTo(1);
        assertThat(only("release.stage").data().get("stagePercent").asInt()).isEqualTo(50);
    }

    @Test
    void theScheduledEvaluatorJudgesEveryActiveCanaryAndLeavesPausedOnesAlone() {
        store.save(Release.canary("rel_a", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));
        store.save(Release.canary("rel_b", "create-vm", "1.9.0", "2.0.1", STAGES, THREE_HOURS_AGO, "ana"));
        store.save(Release.canary("rel_c", "patch-cve", "1.0.0", "1.1.0", STAGES, THREE_HOURS_AGO, "ana").paused(NOW, "ana"));
        when(runs.outcomes(anyString(), anyString(), any())).thenReturn(Mono.just(many(30, () -> ok("x", 1000))));

        handler.evaluateActiveCanaries();

        assertThat(store.find("rel_a").orElseThrow().stageIndex()).isEqualTo(1);
        assertThat(store.find("rel_b").orElseThrow().stageIndex()).isEqualTo(1);
        assertThat(store.find("rel_c").orElseThrow().stageIndex()).isZero();
        assertThat(store.find("rel_c").orElseThrow().jobs()).isEqualTo(Release.Jobs.NONE);
    }

    // ---- commands ----------------------------------------------------------------------------

    @Test
    void commandsNeedEditRightsOnTheWorkflow() {
        store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));

        StepVerifier.create(handler.promote("rel_1").contextWrite(ctx -> CurrentIdentity.with(ctx, ANA)))
                .expectError(ForbiddenException.class)
                .verify();
        assertThat(store.find("rel_1").orElseThrow().status()).isEqualTo(ReleaseStatus.CANARY);

        StepVerifier.create(handler.promote("rel_1").contextWrite(ctx -> CurrentIdentity.with(ctx, MATEO)))
                .assertNext(a -> assertThat(a.status()).isEqualTo("promoted"))
                .verifyComplete();
        assertThat(store.find("rel_1").orElseThrow().history().get(1).by()).isEqualTo("mateo");
    }

    @Test
    void promoteGivesTheCandidateAllTrafficNow() {
        store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));

        StepVerifier.create(handler.promote("rel_1"))
                .assertNext(a -> {
                    assertThat(a.id()).isEqualTo("rel_1");
                    assertThat(a.status()).isEqualTo("promoted");
                    assertThat(a.links()).containsEntry("release", "/api/v1/releases/rel_1");
                })
                .verifyComplete();
        Release r = store.find("rel_1").orElseThrow();
        assertThat(r.status()).isEqualTo(ReleaseStatus.PROMOTED);
        assertThat(r.stagePercent()).isEqualTo(100);
        assertThat(r.endedAt()).isEqualTo(NOW);
        assertThat(r.history().get(1).event()).isEqualTo("promoted");
        assertThat(only("release.promoted").actor()).isEqualTo("platform");
    }

    @Test
    void pauseSendsNewJobsToTheCurrentVersionUntilResumed() {
        store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));

        StepVerifier.create(handler.pause("rel_1")).assertNext(a -> assertThat(a.status()).isEqualTo("paused")).verifyComplete();
        Release paused = store.find("rel_1").orElseThrow();
        assertThat(paused.status()).isEqualTo(ReleaseStatus.PAUSED);
        assertThat(paused.stagePercent()).isZero();
        assertThat(paused.stageIndex()).isZero();
        assertThat(only("release.paused").data().get("stagePercent").asInt()).isZero();

        StepVerifier.create(handler.pause("rel_1")).expectError(ConflictException.class).verify();

        StepVerifier.create(handler.resume("rel_1")).assertNext(a -> assertThat(a.status()).isEqualTo("canary")).verifyComplete();
        Release resumed = store.find("rel_1").orElseThrow();
        assertThat(resumed.status()).isEqualTo(ReleaseStatus.CANARY);
        assertThat(resumed.stagePercent()).isEqualTo(10);
        assertThat(resumed.history()).extracting(Release.HistoryEntry::event).containsExactly("canary started at 10%", "paused", "resumed at 10%");
        assertThat(only("release.resumed").data().get("stagePercent").asInt()).isEqualTo(10);
    }

    @Test
    void rollbackByHandSaysWho() {
        store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));

        StepVerifier.create(handler.rollback("rel_1").contextWrite(ctx -> CurrentIdentity.with(ctx, MATEO)))
                .assertNext(a -> assertThat(a.status()).isEqualTo("rolled-back"))
                .verifyComplete();
        Release r = store.find("rel_1").orElseThrow();
        assertThat(r.status()).isEqualTo(ReleaseStatus.ROLLED_BACK);
        assertThat(r.stagePercent()).isZero();
        assertThat(r.endedAt()).isEqualTo(NOW);
        assertThat(only("release.rolled-back").data().get("reason").asText()).isEqualTo("rolled back by Mateo");
        assertThat(r.history().get(1).event()).as("the history says who, as the event does").isEqualTo("rolled back by Mateo");
        assertThat(store.active("vm-restart")).isEmpty();
    }

    @Test
    void evaluateCommandJudgesAndReportsTheOutcome() {
        store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));
        when(runs.outcomes("vm-restart", "1.2.0", THREE_HOURS_AGO)).thenReturn(Mono.just(many(25, () -> ok("1.2.0", 1000))));
        when(runs.outcomes("vm-restart", "1.1.0", THREE_HOURS_AGO)).thenReturn(Mono.just(many(30, () -> ok("1.1.0", 1000))));

        StepVerifier.create(handler.evaluate("rel_1")).assertNext(a -> assertThat(a.status()).isEqualTo("canary")).verifyComplete();

        assertThat(store.find("rel_1").orElseThrow().stagePercent()).isEqualTo(50);
    }

    @Test
    void finishedReleasesRefuseCommands() {
        store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana").promoted(NOW, "promoted", "ana"));

        StepVerifier.create(handler.promote("rel_1")).expectError(ConflictException.class).verify();
        StepVerifier.create(handler.rollback("rel_1")).expectError(ConflictException.class).verify();
        StepVerifier.create(handler.resume("rel_1")).expectError(ConflictException.class).verify();
        StepVerifier.create(handler.evaluate("rel_1")).expectError(ConflictException.class).verify();
    }

    @Test
    void anUnknownReleaseIsNotFound() {
        StepVerifier.create(handler.promote("rel_missing")).expectError(NotFoundException.class).verify();
    }

    @Test
    void aReleaseWithoutAnOwnerIsLeftToAnAdmin() {
        store.save(Release.canary("rel_gone", "retired-flow", "1.0.0", "1.1.0", STAGES, THREE_HOURS_AGO, "ana"));
        when(workflows.version("retired-flow", "1.1.0")).thenReturn(Mono.empty());
        String gone = "Mateo may not change release rel_gone of retired-flow: its workflow no longer exists, so only an admin may";
        forbidden(handler.promote("rel_gone"), MATEO, gone);
        forbidden(handler.pause("rel_gone"), MATEO, gone);
        forbidden(handler.rollback("rel_gone"), MATEO, gone);
        assertThat(store.find("rel_gone").orElseThrow().status()).isEqualTo(ReleaseStatus.CANARY);
        StepVerifier.create(handler.rollback("rel_gone").contextWrite(ctx -> CurrentIdentity.with(ctx, GONZALO)))
                .assertNext(a -> assertThat(a.status()).isEqualTo("rolled-back"))
                .verifyComplete();

        store.save(Release.canary("rel_p", null, "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana").forProvider("fleet"));
        when(providers.provider("fleet")).thenReturn(Mono.empty());
        forbidden(handler.promote("rel_p"), MATEO, "Mateo may not change release rel_p of fleet: its provider no longer exists, so only an admin may");
        assertThat(store.find("rel_p").orElseThrow().status()).isEqualTo(ReleaseStatus.CANARY);

        store.save(Release.canary("rel_loose", "loose-flow", "1.0.0", "1.1.0", STAGES, THREE_HOURS_AGO, "ana"));
        when(workflows.version("loose-flow", "1.1.0")).thenReturn(Mono.just(record("loose-flow", "1.1.0", null, STAGES)));
        forbidden(handler.pause("rel_loose"), ANA, "Ana may not change release rel_loose of loose-flow: its workflow names no owner, so only an admin may");
        StepVerifier.create(handler.pause("rel_loose").contextWrite(ctx -> CurrentIdentity.with(ctx, GONZALO)))
                .assertNext(a -> assertThat(a.status()).isEqualTo("paused"))
                .verifyComplete();
    }

    private static void forbidden(Mono<?> command, Identity who, String message) {
        StepVerifier.create(command.contextWrite(ctx -> CurrentIdentity.with(ctx, who)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessage(message))
                .verify();
    }

    // ---- provider releases -------------------------------------------------------------------

    @Test
    void aCanaryProviderVersionStartsAReleaseOverTheProvidersCalls() {
        publishProvider("fleet", "1.2.0", "1.1.0", "canary");

        Release r = store.activeProvider("fleet").orElseThrow();
        assertThat(r.provider()).isEqualTo("fleet");
        assertThat(r.workflow()).isNull();
        assertThat(r.subject()).isEqualTo("fleet");
        assertThat(r.from()).isEqualTo("1.1.0");
        assertThat(r.to()).isEqualTo("1.2.0");
        assertThat(r.stages()).containsExactly(10, 50, 100);
        assertThat(r.stagePercent()).isEqualTo(10);
        assertThat(r.gates()).extracting(Release.Gate::detail).contains("0 of 20 calls; 0m of the 2h minimum");
        assertThat(store.active("fleet")).as("no workflow release").isEmpty();
        DomainEvent started = only("release.started");
        assertThat(started.data().get("provider").asText()).isEqualTo("fleet");
        assertThat(started.data().has("workflow")).isFalse();
        assertThat(started.data().get("stagePercent").asInt()).isEqualTo(10);
    }

    @Test
    void aFirstProviderVersionOrAnAllPublishTakesEveryCallAtOnce() {
        publishProvider("fleet", "1.0.0", null, "canary");
        publishProvider("disks", "2.0.0", "1.4.0", "all");

        Release first = store.listProvider("fleet").get(0);
        assertThat(first.status()).isEqualTo(ReleaseStatus.PROMOTED);
        assertThat(first.history()).extracting(Release.HistoryEntry::event).containsExactly("first version takes every call");
        Release all = store.listProvider("disks").get(0);
        assertThat(all.status()).isEqualTo(ReleaseStatus.PROMOTED);
        assertThat(all.strategy()).isEqualTo("all");
        assertThat(all.history()).extracting(Release.HistoryEntry::event).containsExactly("published to every call");
        assertThat(store.active()).isEmpty();
        assertThat(published).filteredOn(e -> e.type().equals("release.promoted")).extracting(e -> e.data().get("provider").asText())
                .containsExactly("fleet", "disks");
    }

    @Test
    void aNewProviderVersionEndsTheCanaryStillRunningAndLeavesWorkflowsAlone() {
        Release old = store.save(Release.canary("rel_old", null, "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana").forProvider("fleet"));
        Release workflow = store.save(Release.canary("rel_wf", "fleet", "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana"));

        publishProvider("fleet", "1.3.0", "1.1.0", "canary");

        Release ended = store.find(old.id()).orElseThrow();
        assertThat(ended.status()).isEqualTo(ReleaseStatus.ROLLED_BACK);
        assertThat(ended.history().get(1).event()).isEqualTo("superseded by 1.3.0");
        Release fresh = store.activeProvider("fleet").orElseThrow();
        assertThat(fresh.from()).isEqualTo("1.1.0");
        assertThat(fresh.to()).isEqualTo("1.3.0");
        assertThat(store.find(workflow.id()).orElseThrow().status()).isEqualTo(ReleaseStatus.CANARY);
        assertThat(only("release.rolled-back").data().get("provider").asText()).isEqualTo("fleet");
    }

    @Test
    void providerGatesJudgeTheCallsOfEachVersionLeavingSandboxCallsOut() {
        Release canary = store.save(Release.canary("rel_p", null, "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana").forProvider("fleet"));
        calls(25, "prod", "1.2.0", true, null, NOW.minus(Duration.ofHours(1)));
        calls(30, "prod", "1.1.0", true, null, NOW.minus(Duration.ofHours(1)));
        calls(10, "dev", "1.2.0", false, "boom", NOW.minus(Duration.ofHours(1)));
        calls(5, "prod", "1.2.0", true, null, NOW.minus(Duration.ofHours(4)));

        StepVerifier.create(handler.judge(canary, "platform"))
                .assertNext(r -> {
                    assertThat(r.stagePercent()).isEqualTo(50);
                    assertThat(r.provider()).isEqualTo("fleet");
                    assertThat(r.gates()).extracting(Release.Gate::detail).contains("0 of 20 calls; 0m of the 2h minimum");
                })
                .verifyComplete();
        Release judged = store.find("rel_p").orElseThrow();
        assertThat(judged.history()).extracting(Release.HistoryEntry::event).containsExactly("canary started at 10%", "advanced to 50%");
        assertThat(only("release.stage").data().get("provider").asText()).isEqualTo("fleet");
    }

    @Test
    void aNewErrorClassOnTheCandidatesCallsRollsTheProviderBack() {
        Release canary = store.save(Release.canary("rel_p", null, "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana").forProvider("fleet"));
        calls(24, "prod", "1.2.0", true, null, NOW.minus(Duration.ofHours(1)));
        calls(1, "prod", "1.2.0", false, "schema_mismatch", NOW.minus(Duration.ofHours(1)));
        calls(30, "prod", "1.1.0", true, null, NOW.minus(Duration.ofHours(1)));
        calls(1, "prod", "1.1.0", false, "timeout", NOW.minus(Duration.ofHours(1)));

        StepVerifier.create(handler.judge(canary, "platform"))
                .assertNext(r -> {
                    assertThat(r.status()).isEqualTo(ReleaseStatus.ROLLED_BACK);
                    assertThat(r.jobs()).isEqualTo(new Release.Jobs(31, 25));
                    assertThat(r.history().get(1).event()).isEqualTo("rolled back: error-types gate failed (new on the candidate: schema_mismatch)");
                })
                .verifyComplete();
        DomainEvent rolledBack = only("release.rolled-back");
        assertThat(rolledBack.data().get("provider").asText()).isEqualTo("fleet");
        assertThat(rolledBack.data().get("to").asText()).isEqualTo("1.2.0");
    }

    @Test
    void aProviderBaselineFallsBackToTheCallsBeforeTheReleaseEvenWithoutAVersion() {
        Release last = store.save(new Release("rel_p", null, "1.1.0", "1.2.0", "canary", ReleaseStatus.CANARY, 2, STAGES, 100,
                NOW.minus(Duration.ofHours(9)), THREE_HOURS_AGO, null, List.of(), Release.Jobs.NONE, List.of(), "fleet"));
        calls(25, "prod", "1.2.0", true, null, NOW.minus(Duration.ofHours(1)));
        calls(30, "prod", null, true, null, NOW.minus(Duration.ofHours(10)));
        calls(7, "prod", null, false, "timeout", NOW.minus(Duration.ofHours(1)));

        StepVerifier.create(handler.judge(last, "platform"))
                .assertNext(r -> {
                    assertThat(r.status()).isEqualTo(ReleaseStatus.PROMOTED);
                    assertThat(r.jobs()).as("calls without a version after the release started are left out").isEqualTo(new Release.Jobs(30, 25));
                })
                .verifyComplete();
        assertThat(only("release.promoted").data().get("provider").asText()).isEqualTo("fleet");
    }

    @Test
    void commandsOnAProviderReleaseNeedEditRightsOnTheProvider() {
        store.save(Release.canary("rel_p", null, "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana").forProvider("fleet"));
        when(providers.provider("fleet")).thenReturn(Mono.just(new ProviderDefinition("iaf.platform/v1",
                new ProviderDefinition.Metadata("fleet", "Fleet API", "team-compute", "1.1.0", null), null)));

        StepVerifier.create(handler.pause("rel_p").contextWrite(ctx -> CurrentIdentity.with(ctx, ANA)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessageContaining("release rel_p of fleet"))
                .verify();
        StepVerifier.create(handler.promote("rel_p").contextWrite(ctx -> CurrentIdentity.with(ctx, MATEO)))
                .assertNext(a -> assertThat(a.status()).isEqualTo("promoted"))
                .verifyComplete();
        DomainEvent promoted = only("release.promoted");
        assertThat(promoted.data().get("provider").asText()).isEqualTo("fleet");
        assertThat(promoted.data().get("from").asText()).isEqualTo("1.1.0");
    }

    @Test
    void theScheduledEvaluatorJudgesProviderCanariesToo() {
        store.save(Release.canary("rel_p", null, "1.1.0", "1.2.0", STAGES, THREE_HOURS_AGO, "ana").forProvider("fleet"));
        calls(25, "prod", "1.2.0", true, null, NOW.minus(Duration.ofHours(1)));
        calls(30, "prod", "1.1.0", true, null, NOW.minus(Duration.ofHours(1)));

        handler.evaluateActiveCanaries();

        assertThat(store.find("rel_p").orElseThrow().stagePercent()).isEqualTo(50);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private void publishProvider(String name, String version, String previous, String strategy) {
        events.publish("provider.published", "provider", name, "mateo", mapper.createObjectNode()
                .put("name", name).put("version", version).put("previous", previous).put("strategy", strategy).put("by", "mateo"));
    }

    private void calls(int n, String environment, String providerVersion, boolean ok, String errorClass, Instant at) {
        for (int i = 0; i < n; i++) {
            telemetry.record(new CallSample(at, "fleet", "vm.get", environment, ok, 200, errorClass, "job_" + i, null, null, providerVersion));
        }
    }

    private void publishWorkflow(String name, String version, String previous, String strategy) {
        events.publish("workflow.published", "workflow", name, "ana", mapper.createObjectNode()
                .put("name", name).put("version", version).put("previous", previous).put("strategy", strategy).put("by", "ana"));
    }

    private DomainEvent only(String type) {
        List<DomainEvent> matching = published.stream().filter(e -> e.type().equals(type)).toList();
        assertThat(matching).as("events of type " + type).hasSize(1);
        return matching.get(0);
    }

    static WorkflowRecord record(String name, String version, String owner, List<Integer> stages) {
        WorkflowDefinition d = new WorkflowDefinition(WorkflowDefinition.API_VERSION,
                new WorkflowDefinition.Metadata(name, null, null, null, null, owner, null, List.of(), version, null),
                null, null, null, List.of(), new WorkflowDefinition.Rollout("canary", stages), null);
        return new WorkflowRecord(name, version, WorkflowStatus.PUBLISHED, "", d, "hash", "workflows/" + owner + "/" + name + ".yaml",
                NOW, "ana", NOW, "ana", null, null);
    }

    static JobOutcome ok(String version, long durationMs) {
        return new JobOutcome(Ids.next("job"), "vm-restart", version, "succeeded", NOW.minusMillis(durationMs), NOW, durationMs, null, null, null);
    }

    static JobOutcome failed(String version, String errorClass) {
        return new JobOutcome(Ids.next("job"), "vm-restart", version, "failed", NOW.minusMillis(500), NOW, 500, errorClass, "step", null);
    }

    static List<JobOutcome> many(int n, Supplier<JobOutcome> outcome) {
        return Stream.generate(outcome).limit(n).toList();
    }
}

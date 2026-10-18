package com.iaf.platform.releases.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.common.demo.DemoReleases;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.domain.Release.Gate;
import com.iaf.platform.releases.domain.ReleaseGates;
import com.iaf.platform.releases.domain.ReleaseStatus;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.adapters.outbound.ChangeProperties;
import com.iaf.platform.runs.adapters.outbound.DemoRunsSeeder;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;

/** The demo releases are judged on the demo jobs, so the Releases page and the Jobs page tell one story. */
class DemoReleaseSeedTest {

    RunsTestWiring w = new RunsTestWiring();
    InMemoryReleaseStore store = new InMemoryReleaseStore();
    PlatformProperties props = new PlatformProperties();
    DemoReleaseSeed seed;
    Instant now;

    @BeforeEach
    void seedTheJobsFirst() {
        props.getContent().setDir("..");
        new DemoRunsSeeder(props, new ChangeProperties(), w.workflows, w.providers, w.resources, w.projection, w.events, w.telemetry, RunsTestWiring.idleEngine(), w.clock).seed();
        seed = new DemoReleaseSeed(store, w.workflows, w.queries, props, w.clock);
        now = w.clock.instant();
    }

    @Test
    void recordsEveryStoryOnceWhoseVersionTheContentHolds() {
        assertThat(seed.seed()).isEqualTo(DemoReleases.STORIES.size());
        assertThat(store.list(null)).extracting(Release::id).containsExactlyInAnyOrderElementsOf(DemoReleases.STORIES.stream().map(s -> s.id()).toList());
        assertThat(seed.seed()).as("a second start adds nothing").isZero();
    }

    @Test
    void theRolledBackCanaryFailedAGateOnTheJobsOfItsStage() {
        seed.seed();
        Release r = store.find("rel_demo_mongo_rollback").orElseThrow();
        assertThat(r.status()).isEqualTo(ReleaseStatus.ROLLED_BACK);
        assertThat(r.workflow()).isEqualTo("mongodb-upgrade");
        assertThat(r.from()).isEqualTo("3.0.2");
        assertThat(r.to()).isEqualTo("3.1.0");
        assertThat(r.stagePercent()).as("the candidate takes no traffic once rolled back").isZero();
        assertThat(r.stages()).as("the stages of the workflow's release rollout").isEqualTo(List.of(10, 50, 100));
        assertThat(r.startedAt()).isCloseTo(now.minus(Duration.ofDays(8)), within(5, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(r.endedAt()).isCloseTo(now.minus(Duration.ofDays(8)).plus(Duration.ofMinutes(102)), within(5, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(r.gates()).extracting(Gate::id).containsExactly("success-rate", "p95", "error-types", "sample");
        Gate errorTypes = r.gates().stream().filter(g -> g.id().equals("error-types")).findFirst().orElseThrow();
        assertThat(errorTypes.passed()).isFalse();
        assertThat(errorTypes.detail()).contains("replica_set_not_ready");
        assertThat(r.jobs().candidate()).isGreaterThanOrEqualTo(2);
        assertThat(r.jobs().current()).isGreaterThanOrEqualTo(2);
        assertThat(r.history()).isNotEmpty();
        assertThat(r.history().get(0).event()).isEqualTo("canary started at 10%");
        assertThat(r.history().get(0).by()).isEqualTo("ana");
        Release.HistoryEntry last = r.history().get(r.history().size() - 1);
        assertThat(last.event()).startsWith("rolled back: ").contains("gate failed");
        assertThat(last.by()).isEqualTo(Identity.SYSTEM.id());
        // the job the gate saw is in the history, on the candidate, with that error
        List<Job> failed = w.projection.all().stream()
                .filter(j -> j.workflow().equals("mongodb-upgrade") && "3.1.0".equals(j.version()) && j.status() == JobStatus.FAILED).toList();
        assertThat(failed).anyMatch(j -> "replica_set_not_ready".equals(j.errorClass()) && "verify".equals(j.failedStep())
                && j.startedAt().isBefore(r.endedAt()));
    }

    @Test
    void theRepublishWentOutToEveryoneAtOnce() {
        seed.seed();
        Release r = store.find("rel_demo_mongo_republish").orElseThrow();
        assertThat(r.status()).isEqualTo(ReleaseStatus.PROMOTED);
        assertThat(r.strategy()).isEqualTo(DemoReleases.ALL);
        assertThat(r.startedAt()).isEqualTo(r.endedAt());
        assertThat(r.history()).extracting(Release.HistoryEntry::by).containsOnly("ana");
    }

    @Test
    void theCanaryPromotedByHandPassedEveryGateItCouldJudge() {
        seed.seed();
        Release r = store.find("rel_demo_create_vm").orElseThrow();
        assertThat(r.status()).isEqualTo(ReleaseStatus.PROMOTED);
        assertThat(r.endedAt()).isCloseTo(now.minus(Duration.ofHours(26)), within(5, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(r.gates()).as("no gate contradicts the owner's decision").noneMatch(g -> Boolean.FALSE.equals(g.passed()));
        assertThat(r.jobs().candidate()).isGreaterThanOrEqualTo(1);
        Release.HistoryEntry last = r.history().get(r.history().size() - 1);
        assertThat(last.event()).isEqualTo("promoted");
        assertThat(last.by()).isEqualTo("gonzalo");
    }

    @Test
    void theRunningCanaryIsWaitingForItsSampleNotFailingAGate() {
        seed.seed();
        Release r = store.find("rel_demo_vm_restart").orElseThrow();
        assertThat(r.status()).isEqualTo(ReleaseStatus.CANARY);
        assertThat(r.active()).isTrue();
        assertThat(r.stagePercent()).isEqualTo(10);
        assertThat(r.stages()).isEqualTo(List.of(10, 50, 100));
        assertThat(r.endedAt()).isNull();
        assertThat(r.gates()).noneMatch(g -> Boolean.FALSE.equals(g.passed()));
        Gate sample = r.gates().stream().filter(g -> g.id().equals("sample")).findFirst().orElseThrow();
        assertThat(sample.passed()).as("80 minutes in, the two-hour minimum is not met").isNull();
        assertThat(r.jobs().candidate()).isGreaterThanOrEqualTo(1).isLessThan(ReleaseGates.MIN_JOBS);
        assertThat(store.active("vm-restart")).map(Release::id).contains("rel_demo_vm_restart");
    }
}

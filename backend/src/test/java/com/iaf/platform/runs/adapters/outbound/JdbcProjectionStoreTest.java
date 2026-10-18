package com.iaf.platform.runs.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobEvent;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.ports.outbound.ProjectionStore;
import com.iaf.platform.runs.ports.outbound.ProjectionStore.JobQuery;

class JdbcProjectionStoreTest {

    abstract static class OnDatabase extends ProjectionStoreContract {

        abstract TestDatabase db();

        @Override
        ProjectionStore newStore() {
            return new JdbcProjectionStore(db().jdbc());
        }

        @Override
        ProjectionStore reopen() {
            return new JdbcProjectionStore(new Jdbc(db().dataSource()));
        }

        @Test
        void keepsEachDefinitionSnapshotOnce() {
            ProjectionStore store = newStore();
            store.save(job("job_1", "vm-restart", JobStatus.RUNNING, T0));
            store.save(job("job_2", "vm-restart", JobStatus.RUNNING, T0));
            store.save(job("job_3", "patch-cve", JobStatus.RUNNING, T0));
            reopen().save(job("job_4", "vm-restart", JobStatus.RUNNING, T0));
            assertThat(db().jdbc().query("SELECT hash FROM runs_definitions", (rs, i) -> rs.getString(1))).hasSize(2);
            assertThat(db().jdbc().query("SELECT doc FROM runs_jobs", (rs, i) -> rs.getString(1))).noneMatch(doc -> doc.contains("\"definition\""));
            assertThat(reopen().job("job_3").orElseThrow().definition()).isEqualTo(PATCH);
        }

        @Test
        void aJobWhoseDefinitionRowIsMissingIsStillListedWithoutItsSnapshot() {
            ProjectionStore store = newStore();
            store.save(job("job_1", "vm-restart", JobStatus.RUNNING, T0));
            store.save(job("job_2", "patch-cve", JobStatus.RUNNING, T0.plusSeconds(1)));
            String patchHash = db().jdbc().first("SELECT definition_hash FROM runs_jobs WHERE id = ?", (rs, i) -> rs.getString(1), "job_2").orElseThrow();
            db().jdbc().update("DELETE FROM runs_definitions WHERE hash = ?", patchHash);

            ProjectionStore restarted = reopen();

            assertThat(restarted.jobs(JobQuery.ALL)).extracting(Job::id).containsExactly("job_2", "job_1");
            assertThat(restarted.job("job_2").orElseThrow().definition()).isNull();
            assertThat(restarted.job("job_2").orElseThrow().workflow()).isEqualTo("patch-cve");
            assertThat(restarted.job("job_1").orElseThrow().definition()).isNotNull();
        }

        @Test
        void keepsEverySeededDemoJobExactly() {
            InMemoryProjectionStore reference = new InMemoryProjectionStore();
            RunsTestWiring w = new RunsTestWiring(new Mirror(reference, newStore()));
            PlatformProperties props = new PlatformProperties();
            props.getContent().setDir("..");
            new DemoRunsSeeder(props, new ChangeProperties(), w.workflows, w.providers, w.resources, w.projection, w.events, w.telemetry, RunsTestWiring.idleEngine(), w.clock).seed();

            ProjectionStore restarted = reopen();
            List<Job> all = reference.jobs(JobQuery.ALL);
            assertThat(all).hasSizeGreaterThanOrEqualTo(60);
            assertThat(restarted.jobs(JobQuery.ALL)).extracting(Job::id).containsExactlyElementsOf(all.stream().map(Job::id).toList());
            for (Job job : all) {
                Job read = restarted.job(job.id()).orElseThrow();
                assertThat(json(read)).as(job.id()).isEqualTo(json(job));
                assertThat(read.definition()).as(job.id()).isEqualTo(job.definition());
                assertThat(json(restarted.history(job.id()))).as(job.id()).isEqualTo(json(reference.history(job.id())));
                assertThat(restarted.children(job.id())).extracting(Job::id)
                        .containsExactlyElementsOf(reference.children(job.id()).stream().map(Job::id).toList());
            }
            assertThat(json(restarted.approvals())).isEqualTo(json(reference.approvals()));
            assertThat(restarted.approvals()).isNotEmpty();
        }

        @Test
        void aJobsPageHistoryAndNumberingSurviveARestart() {
            Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
            EventBus bus = new EventBus(clock);
            JobProjection projection = new JobProjection(bus, newStore());
            projection.register();
            projection.put(job("job_1", "vm-restart", JobStatus.QUEUED, T0));
            bus.publish("job.started", "job", "job_1", "ana", JSON.createObjectNode().put("workflow", "vm-restart"));
            bus.publish("job.step", "job", "job_1", "platform", JSON.createObjectNode().put("step", "stop").put("status", "running"));

            EventBus restartedBus = new EventBus(clock);
            JobProjection restarted = new JobProjection(restartedBus, reopen());
            restarted.register();
            assertThat(restarted.get("job_1").orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
            assertThat(restarted.get("job_1").orElseThrow().step("stop")).isNotNull();
            assertThat(restarted.history("job_1")).extracting(JobEvent::seq).containsExactly(1L, 2L);

            List<JobEvent> seen = new CopyOnWriteArrayList<>();
            restarted.events("job_1").subscribe(seen::add);
            restartedBus.publish("job.finished", "job", "job_1", "platform", JSON.createObjectNode().put("status", "succeeded"));

            assertThat(seen).extracting(JobEvent::seq).as("history, then live, numbered on").containsExactly(1L, 2L, 3L);
            assertThat(restarted.get("job_1").orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(restarted.events("job_1").collectList().block()).extracting(JobEvent::seq).containsExactly(1L, 2L, 3L);
            assertThat(new JobProjection(new EventBus(clock), reopen()).history("job_1")).hasSize(3);
        }

        private static String json(Object value) {
            try {
                return Jdbc.mapper().writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Writes go to both stores, reads come from the reference: the copy must end up the same. */
    record Mirror(ProjectionStore reference, ProjectionStore copy) implements ProjectionStore {

        @Override
        public Optional<Job> job(String id) {
            return reference.job(id);
        }

        @Override
        public void save(Job job) {
            reference.save(job);
            copy.save(job);
        }

        @Override
        public List<Job> jobs(JobQuery query) {
            return reference.jobs(query);
        }

        @Override
        public List<Job> children(String parentId) {
            return reference.children(parentId);
        }

        @Override
        public Optional<Approval> approval(String id) {
            return reference.approval(id);
        }

        @Override
        public List<Approval> approvals() {
            return reference.approvals();
        }

        @Override
        public void append(JobEvent event) {
            reference.append(event);
            copy.append(event);
        }

        @Override
        public List<JobEvent> history(String jobId) {
            return reference.history(jobId);
        }

        @Override
        public long lastSeq(String jobId) {
            return reference.lastSeq(jobId);
        }
    }

    @Nested
    class OnH2 extends OnDatabase {
        @RegisterExtension
        final TestDatabase db = TestDatabase.h2();

        @Override
        TestDatabase db() {
            return db;
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = TestDatabase.POSTGRES_URL, matches = ".+")
    class OnPostgres extends OnDatabase {
        @RegisterExtension
        final TestDatabase db = TestDatabase.postgres();

        @Override
        TestDatabase db() {
            return db;
        }
    }
}

package com.iaf.platform.workflows.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

/**
 * What every {@link WorkflowStorePort} does, run against the in-memory store and the JDBC one
 * (H2, and PostgreSQL when {@code PLATFORM_TEST_POSTGRES_URL} is set).
 */
abstract class WorkflowStoreContract {

    static final Instant T0 = Instant.parse("2026-10-18T09:15:30.123456789Z");
    static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    WorkflowStorePort store;

    /** An empty store. */
    abstract WorkflowStorePort newStore();

    /** A store seeded from the content dir at {@code root}, as at startup. */
    abstract WorkflowStorePort seededFrom(Path root);

    /** A new store over the same storage, as after a restart; null when the storage does not outlive the store. */
    abstract WorkflowStorePort reopen();

    @BeforeEach
    void setUp() {
        store = newStore();
    }

    static WorkflowRecord record(String name, String version, WorkflowStatus status) {
        WorkflowDefinition sample = SampleContent.workflow("vm-restart");
        WorkflowDefinition d = new WorkflowDefinition(sample.apiVersion(),
                new WorkflowDefinition.Metadata(name, sample.metadata().title(), sample.metadata().noun(), sample.metadata().verb(),
                        sample.metadata().persona(), sample.metadata().owner(), sample.metadata().folder(), sample.metadata().tags(), version,
                        sample.metadata().description()),
                sample.api(), sample.mcp(), sample.policy(), sample.triggers(), sample.rollout(), sample.workflow());
        return WorkflowRecord.of(d, status, "workflows/team-compute/" + name + ".yaml", T0, "gonzalo");
    }

    @Test
    void readsAVersionBackExactlyAsSaved() {
        WorkflowRecord saved = record("restart", "1.2.0", WorkflowStatus.DRAFT).withNotes("first cut");
        store.save(saved);
        assertThat(store.get("restart", "1.2.0")).contains(saved);
        assertThat(store.get("restart", "9.9.9")).isEmpty();
        assertThat(store.get("unknown", "1.2.0")).isEmpty();
    }

    @Test
    void listsVersionsOldestFirstBySemanticVersion() {
        store.save(record("restart", "1.10.0", WorkflowStatus.DRAFT));
        store.save(record("restart", "1.9.0", WorkflowStatus.PUBLISHED));
        store.save(record("restart", "1.2.0", WorkflowStatus.RETIRED));
        assertThat(store.versions("restart")).extracting(WorkflowRecord::version).containsExactly("1.2.0", "1.9.0", "1.10.0");
        assertThat(store.versions("unknown")).isEmpty();
    }

    @Test
    void listsEveryVersionByNameThenVersion() {
        store.save(record("zeta", "1.0.0", WorkflowStatus.PUBLISHED));
        store.save(record("alpha", "2.0.0", WorkflowStatus.DRAFT));
        store.save(record("alpha", "1.0.0", WorkflowStatus.PUBLISHED));
        assertThat(store.all()).extracting(r -> r.name() + "@" + r.version()).containsExactly("alpha@1.0.0", "alpha@2.0.0", "zeta@1.0.0");
    }

    @Test
    void publishedIsTheHighestPublishedVersionAndDraftTheHighestDraft() {
        store.save(record("restart", "1.0.0", WorkflowStatus.PUBLISHED));
        store.save(record("restart", "1.1.0", WorkflowStatus.PUBLISHED));
        store.save(record("restart", "1.2.0", WorkflowStatus.CANDIDATE));
        store.save(record("restart", "1.3.0", WorkflowStatus.DRAFT));
        store.save(record("restart", "1.0.1", WorkflowStatus.DRAFT));
        assertThat(store.published("restart")).map(WorkflowRecord::version).contains("1.1.0");
        assertThat(store.draft("restart")).map(WorkflowRecord::version).contains("1.3.0");
        assertThat(store.published("unknown")).isEmpty();
        assertThat(store.draft("unknown")).isEmpty();
    }

    @Test
    void saveReplacesTheSameNameAndVersion() {
        store.save(record("restart", "1.2.0", WorkflowStatus.DRAFT));
        WorkflowRecord published = record("restart", "1.2.0", WorkflowStatus.DRAFT).published(T0.plusSeconds(60), "ana", "rel_1");
        store.save(published);
        assertThat(store.versions("restart")).containsExactly(published);
    }

    @Test
    void deleteRemovesOneVersionAndTheNameWithItsLast() {
        store.save(record("restart", "1.0.0", WorkflowStatus.PUBLISHED));
        store.save(record("restart", "1.1.0", WorkflowStatus.DRAFT));
        store.delete("restart", "1.1.0");
        assertThat(store.versions("restart")).extracting(WorkflowRecord::version).containsExactly("1.0.0");
        store.delete("restart", "1.0.0");
        store.delete("restart", "1.0.0");
        assertThat(store.versions("restart")).isEmpty();
        assertThat(store.all()).isEmpty();
    }

    @Test
    void seedsEveryContentWorkflowAsAPublishedVersion() {
        WorkflowStorePort seeded = seededFrom(SampleContent.ROOT);
        List<ContentFiles.ContentFile> files = ContentFiles.ofKind(SampleContent.ROOT, WorkflowDefinition.KIND);
        assertThat(seeded.all()).hasSize(files.size()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(WorkflowStatus.PUBLISHED);
            assertThat(r.updatedBy()).isEqualTo("content");
            assertThat(r.path()).startsWith("workflows/");
        });
        WorkflowDefinition vmRestart = SampleContent.workflow("vm-restart");
        WorkflowRecord stored = seeded.published("vm-restart").orElseThrow();
        assertThat(stored.definition()).isEqualTo(vmRestart);
        assertThat(stored.yaml()).isEqualTo(WorkflowYaml.toYaml(vmRestart));
    }

    @Test
    void keepsWhatItWroteAcrossARestart() {
        WorkflowRecord saved = record("restart", "1.2.0", WorkflowStatus.PUBLISHED).withNotes("survives");
        store.save(saved);
        // a version a release rollback took out stays marked, so nothing resolves to it after a restart either
        WorkflowRecord rolledBack = record("restart", "1.3.0", WorkflowStatus.PUBLISHED).asRolledBack();
        store.save(rolledBack);
        assertThat(store.get("restart", "1.3.0")).contains(rolledBack);
        WorkflowStorePort after = reopen();
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.get("restart", "1.2.0")).contains(saved);
        assertThat(after.published("restart")).contains(saved);
        assertThat(after.get("restart", "1.3.0")).contains(rolledBack);
        assertThat(after.get("restart", "1.3.0").orElseThrow().isRunnable()).isFalse();
    }
}

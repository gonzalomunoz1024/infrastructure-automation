package com.iaf.platform.workflows.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

class JdbcWorkflowStoreTest {

    static final String HELLO = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: hello, title: Hello, noun: thing, verb: greet, owner: team-a, version: 1.0.0 }
            workflow:
              do:
                - say:
                    notify: { channel: "#x", message: "hello" }
            """;

    abstract static class OnDatabase extends WorkflowStoreContract {

        abstract TestDatabase db();

        @TempDir
        Path content;

        @Override
        WorkflowStorePort newStore() {
            return new JdbcWorkflowStore(db().jdbc(), (Path) null, CLOCK);
        }

        @Override
        WorkflowStorePort seededFrom(Path root) {
            JdbcWorkflowStore s = new JdbcWorkflowStore(db().jdbc(), root, CLOCK);
            s.init();
            return s;
        }

        @Override
        WorkflowStorePort reopen() {
            return new JdbcWorkflowStore(db().jdbc(), (Path) null, CLOCK);
        }

        @Test
        void anotherInstanceSeesWritesAtOnce() {
            WorkflowStorePort other = reopen();
            assertThat(other.all()).isEmpty();
            store.save(record("restart", "1.0.0", WorkflowStatus.PUBLISHED));
            assertThat(other.versions("restart")).hasSize(1);
            other.delete("restart", "1.0.0");
            assertThat(store.all()).isEmpty();
        }

        @Test
        void keepsEverySampleDefinitionExactlyAsParsed() {
            WorkflowStorePort seeded = seededFrom(SampleContent.ROOT);
            WorkflowStorePort restarted = reopen();
            for (ContentFiles.ContentFile f : ContentFiles.ofKind(SampleContent.ROOT, WorkflowDefinition.KIND)) {
                WorkflowDefinition parsed = WorkflowYaml.parse(f.yaml());
                assertThat(restarted.published(parsed.name()).orElseThrow().definition()).as(f.relative(SampleContent.ROOT)).isEqualTo(parsed);
            }
            assertThat(seeded.all()).hasSameSizeAs(restarted.all());
        }

        @Test
        void seedingAgainAddsNothingAndNeverResetsAStoredVersion() throws Exception {
            write(HELLO);
            seededFrom(content);
            WorkflowRecord hello = store.get("hello", "1.0.0").orElseThrow();
            store.save(hello.withStatus(WorkflowStatus.RETIRED));
            assertThat(((JdbcWorkflowStore) seededFrom(content)).seed(content)).isZero();
            assertThat(store.get("hello", "1.0.0")).map(WorkflowRecord::status).contains(WorkflowStatus.RETIRED);
        }

        @Test
        void aVersionStillAsSeededTakesItsChangedFileAndKeepsItsStatus() throws Exception {
            write(HELLO);
            seededFrom(content);
            store.save(store.get("hello", "1.0.0").orElseThrow().withStatus(WorkflowStatus.RETIRED));
            write(HELLO.replace("message: \"hello\"", "message: \"hello there\""));
            seededFrom(content);
            WorkflowRecord hello = store.get("hello", "1.0.0").orElseThrow();
            assertThat(hello.yaml()).contains("hello there");
            assertThat(hello.status()).isEqualTo(WorkflowStatus.RETIRED);
        }

        @Test
        void aVersionChangedThroughThePlatformIgnoresItsFile() throws Exception {
            write(HELLO);
            seededFrom(content);
            WorkflowRecord republished = store.get("hello", "1.0.0").orElseThrow().published(CLOCK.instant().plusSeconds(5), "ana", "rel_1");
            store.save(republished);
            write(HELLO.replace("message: \"hello\"", "message: \"changed on disk\""));
            seededFrom(content);
            assertThat(store.get("hello", "1.0.0")).contains(republished);
        }

        private void write(String yaml) throws Exception {
            Path file = content.resolve("workflows/team-a/hello.yaml");
            Files.createDirectories(file.getParent());
            Files.writeString(file, yaml);
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

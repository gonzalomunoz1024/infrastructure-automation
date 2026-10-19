package com.iaf.platform.workflows.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;
import com.mongodb.MongoSocketReadException;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoWorkflowStoreTest extends WorkflowStoreContract {

    static final String HELLO = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: hello, title: Hello, noun: thing, verb: greet, owner: team-a, version: 1.0.0 }
            workflow:
              do:
                - say:
                    notify: { channel: "#x", message: "hello" }
            """;

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @TempDir
    Path content;

    @Override
    WorkflowStorePort newStore() {
        return new MongoWorkflowStore(db.mongo(), (Path) null, CLOCK);
    }

    @Override
    WorkflowStorePort seededFrom(Path root) {
        MongoWorkflowStore s = new MongoWorkflowStore(db.mongo(), root, CLOCK);
        s.init();
        return s;
    }

    @Override
    WorkflowStorePort reopen() {
        return new MongoWorkflowStore(db.mongo(), (Path) null, CLOCK);
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
    void aWriteThatThrowsAfterLandingStillMovesTheRevision() {
        WorkflowStorePort other = reopen();
        assertThat(other.all()).isEmpty();
        WorkflowStorePort saving = new MongoWorkflowStore(db.landingThenFailing(MongoWorkflowStore.COLLECTION, "replaceOne"), (Path) null, CLOCK);
        assertThatThrownBy(() -> saving.save(record("restart", "1.0.0", WorkflowStatus.PUBLISHED))).isInstanceOf(MongoSocketReadException.class);
        assertThat(db.mongo().revision(MongoWorkflowStore.STORE)).isEqualTo(1);
        assertThat(other.published("restart")).as("another instance sees the version at once").isPresent();

        WorkflowStorePort deleting = new MongoWorkflowStore(db.landingThenFailing(MongoWorkflowStore.COLLECTION, "deleteOne"), (Path) null, CLOCK);
        assertThatThrownBy(() -> deleting.delete("restart", "1.0.0")).isInstanceOf(MongoSocketReadException.class);
        assertThat(db.mongo().revision(MongoWorkflowStore.STORE)).isEqualTo(2);
        assertThat(other.published("restart")).as("and sees it gone at once").isEmpty();
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
        assertThat(((MongoWorkflowStore) seededFrom(content)).seed(content)).isZero();
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

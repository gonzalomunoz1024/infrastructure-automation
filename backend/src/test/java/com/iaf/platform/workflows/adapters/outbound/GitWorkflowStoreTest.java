package com.iaf.platform.workflows.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** The Git store against a temporary repository: drafts on change branches, merges on main, history readable. */
class GitWorkflowStoreTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final String PATH = "workflows/team-a/hello.yaml";
    static final String HELLO = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: hello, title: Hello, noun: thing, verb: greet, owner: team-a, version: 1.0.0 }
            workflow:
              do:
                - say:
                    notify: { channel: "#x", message: "hello" }
            """;

    @TempDir
    Path dir;
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void seedRepository() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            Path file = dir.resolve(PATH);
            Files.createDirectories(file.getParent());
            Files.writeString(file, HELLO);
            git.add().addFilepattern("workflows").call();
            git.commit().setMessage("seed").setAuthor("seed", "seed@example.com").setCommitter("seed", "seed@example.com").setSign(false).call();
        }
    }

    static WorkflowRecord draft(String message, String version) {
        WorkflowDefinition d = WorkflowYaml.parse(HELLO.replace("message: \"hello\"", "message: \"" + message + "\"")).withVersion(version);
        return WorkflowRecord.of(d, WorkflowStatus.DRAFT, PATH, NOW, "ana");
    }

    @Test
    void publishedFilesOnMainBecomeRecords() {
        GitWorkflowStore store = new GitWorkflowStore(dir, clock);
        store.init();
        assertThat(store.mainBranch()).isEqualTo("main");
        assertThat(store.all()).hasSize(1);
        WorkflowRecord hello = store.published("hello").orElseThrow();
        assertThat(hello.version()).isEqualTo("1.0.0");
        assertThat(hello.path()).isEqualTo(PATH);
        assertThat(hello.updatedBy()).isEqualTo("seed");
        assertThat(hello.publishedAt()).isNotNull();
        assertThat(store.fileAt("main", PATH)).contains(HELLO);
        store.close();
    }

    @Test
    void draftsLiveOnAChangeBranchAndMergesLandOnMainAsMergeCommits() throws Exception {
        GitWorkflowStore store = new GitWorkflowStore(dir, clock);
        store.init();
        WorkflowRecord draft = draft("hello again", "1.0.1");
        store.save(draft);

        assertThat(store.fileAt("change/hello-1.0.1", PATH)).contains(draft.yaml());
        assertThat(store.fileAt("main", PATH)).as("main is untouched by a draft").contains(HELLO);
        assertThat(store.versions("hello")).extracting(WorkflowRecord::version, WorkflowRecord::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1.0.0", WorkflowStatus.PUBLISHED), org.assertj.core.groups.Tuple.tuple("1.0.1", WorkflowStatus.DRAFT));

        // a status change alone (draft -> candidate) commits nothing new
        store.save(draft.withStatus(WorkflowStatus.CANDIDATE));
        try (Git git = Git.open(dir.toFile())) {
            int commits = 0;
            for (RevCommit ignored : git.log().add(git.getRepository().resolve("refs/heads/change/hello-1.0.1")).call()) {
                commits++;
            }
            assertThat(commits).isEqualTo(2);
        }
        assertThat(store.get("hello", "1.0.1").orElseThrow().status()).isEqualTo(WorkflowStatus.CANDIDATE);

        // a new revision of the draft is a second commit on the branch
        WorkflowRecord revised = draft("hello once more", "1.0.1");
        store.save(revised);
        assertThat(store.fileAt("change/hello-1.0.1", PATH)).contains(revised.yaml());

        // merge: the previous version retires, the candidate lands on main, the branch goes away
        store.save(store.published("hello").orElseThrow().withStatus(WorkflowStatus.RETIRED));
        store.save(revised.withStatus(WorkflowStatus.CANDIDATE).published(NOW, "ana", null));
        assertThat(store.fileAt("main", PATH)).contains(revised.yaml());
        assertThat(store.fileAt("change/hello-1.0.1", PATH)).isEmpty();
        assertThat(store.published("hello").orElseThrow().version()).isEqualTo("1.0.1");
        try (Git git = Git.open(dir.toFile())) {
            RevCommit head = git.log().setMaxCount(1).call().iterator().next();
            assertThat(head.getParentCount()).as("a merge commit of the change branch").isEqualTo(2);
            assertThat(head.getFullMessage()).isEqualTo("Publish hello 1.0.1");
            assertThat(head.getAuthorIdent().getName()).isEqualTo("ana");
            assertThat(git.status().call().isClean()).as("the checked-out working tree follows main").isTrue();
            assertThat(git.getRepository().getRefDatabase().getRefsByPrefix("refs/heads/change/")).isEmpty();
        }
        assertThat(Files.readString(dir.resolve(PATH))).isEqualTo(revised.yaml());
        store.close();

        // a fresh store reads every version back from git: the merged file on main, the old one from history
        GitWorkflowStore again = new GitWorkflowStore(dir, clock);
        again.init();
        assertThat(again.versions("hello")).extracting(WorkflowRecord::version, WorkflowRecord::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1.0.0", WorkflowStatus.RETIRED), org.assertj.core.groups.Tuple.tuple("1.0.1", WorkflowStatus.PUBLISHED));
        assertThat(again.get("hello", "1.0.0").orElseThrow().yaml()).isEqualTo(WorkflowYaml.toYaml(WorkflowYaml.parse(HELLO)));
        assertThat(again.get("hello", "1.0.1").orElseThrow().yaml()).isEqualTo(revised.yaml());
        again.close();
    }

    @Test
    void deletingADraftRemovesItsBranchAndANewWorkflowGetsItsOwnFile() throws Exception {
        GitWorkflowStore store = new GitWorkflowStore(dir, clock);
        store.init();
        store.save(draft("bye", "1.0.1"));
        assertThat(store.fileAt("change/hello-1.0.1", PATH)).isPresent();
        store.delete("hello", "1.0.1");
        assertThat(store.fileAt("change/hello-1.0.1", PATH)).isEmpty();
        assertThat(store.versions("hello")).hasSize(1);

        WorkflowDefinition other = WorkflowYaml.parse(HELLO.replace("name: hello", "name: other").replace("owner: team-a", "owner: team-b"));
        store.save(WorkflowRecord.of(other, WorkflowStatus.DRAFT, null, NOW, "mateo"));
        assertThat(store.get("other", "1.0.0").orElseThrow().path()).isEqualTo("workflows/team-b/other.yaml");
        assertThat(store.fileAt("change/other-1.0.0", "workflows/team-b/other.yaml")).isPresent();
        // drafts survive a restart as drafts
        GitWorkflowStore again = new GitWorkflowStore(dir, clock);
        again.init();
        assertThat(again.draft("other").orElseThrow().status()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(again.draft("other").orElseThrow().updatedBy()).isEqualTo("mateo");
        again.close();
        store.close();
    }

    @Test
    void aVersionUnderReviewIsACandidateAgainAfterARestart() {
        GitWorkflowStore store = new GitWorkflowStore(dir, clock);
        store.init();
        store.save(draft("review me", "1.0.1").withStatus(WorkflowStatus.CANDIDATE));
        store.save(draft("not yet", "1.1.0"));
        store.close();
        InMemoryChangeRequests changeRequests = new InMemoryChangeRequests();
        changeRequests.save(new ChangeRequest("cr_1", ChangeRequest.Kind.WORKFLOW, "hello", "1.0.1", "change/hello-1.0.1", "Update Hello 1.0.1", "ana",
                ChangeRequest.Status.OPEN, List.of(), "all", "", "", NOW, null, null, null));

        GitWorkflowStore restarted = new GitWorkflowStore(dir, clock);
        restarted.setChangeRequests(changeRequests);
        restarted.init();
        assertThat(restarted.get("hello", "1.0.1").orElseThrow().status()).as("its change request is open").isEqualTo(WorkflowStatus.CANDIDATE);
        assertThat(restarted.get("hello", "1.1.0").orElseThrow().status()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(restarted.draft("hello").orElseThrow().version()).isEqualTo("1.1.0");
        restarted.close();

        changeRequests.save(changeRequests.get("cr_1").orElseThrow().withStatus(ChangeRequest.Status.CLOSED, NOW, "ana"));
        GitWorkflowStore closed = new GitWorkflowStore(dir, clock);
        closed.setChangeRequests(changeRequests);
        closed.init();
        assertThat(closed.get("hello", "1.0.1").orElseThrow().status()).as("its change request was closed").isEqualTo(WorkflowStatus.DRAFT);
        closed.close();
    }

    @Test
    void aContentDirWithoutARepositoryIsInitialisedAndCommitted() throws Exception {
        Path fresh = dir.resolve("fresh");
        Path file = fresh.resolve("workflows/team-a/hello.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, HELLO);
        GitWorkflowStore store = new GitWorkflowStore(fresh, clock);
        store.init();
        assertThat(Files.isDirectory(fresh.resolve(".git"))).isTrue();
        assertThat(store.published("hello")).isPresent();
        try (Git git = Git.open(fresh.toFile())) {
            assertThat(git.log().call().iterator().next().getFullMessage()).isEqualTo("Import content");
            assertThat(git.status().call().isClean()).isTrue();
        }
        store.close();
    }
}

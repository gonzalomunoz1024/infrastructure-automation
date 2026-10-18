package com.iaf.platform.runs.adapters.outbound.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.adapters.artifacts.FilesystemArtifactStore;
import com.iaf.platform.common.adapters.git.JGitRemotes;
import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.ArtifactsProperties;
import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.config.RunnersProperties;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.RunRequest;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.testing.Archives;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The ansible runner against fake executables: each records its arguments, environment and the
 * private data directory it was handed, then prints canned events (shaped as ansible-runner 2.x
 * prints them). Playbooks come from a local git repository through the real JGit remotes.
 */
class AnsibleRunnerAutomationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final PersonIdent WHO = new PersonIdent("Test", "test@example.com");
    static final String KEY = "fake-ssh-key-for-tests\nsecond-line-of-the-fake-key";

    @TempDir
    Path tmp;
    Path bin;
    Path record;
    Path work;
    Git origin;
    String repo;
    String c1;
    String c2;
    RunnersProperties props;
    ArtifactStorePort artifacts;
    ProviderQueries providers;
    AnsibleRunnerAutomation runner;
    final List<String> logged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        bin = Files.createDirectories(tmp.resolve("bin"));
        record = Files.createDirectories(tmp.resolve("record"));
        work = tmp.resolve("work");
        fake("ansible-runner", """
                printf '%s\\n' "$@" > "$R/argv"
                env | sort > "$R/env"
                rm -rf "$R/pdd"; cp -r "$2" "$R/pdd"
                stat -c '%a' "$2/.credentials/ssh_key" > "$R/key-mode" 2>/dev/null
                if [ -f "$R/sleep" ]; then sleep "$(cat "$R/sleep")"; fi
                cat "$R/events.jsonl" 2>/dev/null
                exit "$(cat "$R/exit-code" 2>/dev/null || echo 0)"
                """);
        fake("ansible-playbook", """
                printf '%s\\n' "$@" > "$R/playbook-argv"
                env | sort > "$R/playbook-env"
                cat "$R/doc.json"
                exit "$(cat "$R/exit-code" 2>/dev/null || echo 0)"
                """);
        fake("ansible-galaxy", """
                echo "$*" >> "$R/galaxy-calls"
                prev=""
                for a in "$@"; do if [ "$prev" = "-p" ]; then mkdir -p "$a/installed"; fi; prev="$a"; done
                """);
        fake("docker", """
                printf '%s\\n' "$@" > "$R/docker-argv"
                cat "$R/events.jsonl" 2>/dev/null
                """);

        Path originDir = tmp.resolve("origin");
        origin = Git.init().setDirectory(originDir.toFile()).setInitialBranch("main").call();
        Files.createDirectories(originDir.resolve("site"));
        Files.writeString(originDir.resolve("site/restart.yml"), "- hosts: all\n  tasks: []\n");
        Files.createDirectories(originDir.resolve("inventories"));
        Files.writeString(originDir.resolve("inventories/prod.yml"), "all: {hosts: {web1: {}}}\n");
        c1 = commit("first");
        origin.tag().setName("v1").setAnnotated(false).call();
        Files.writeString(originDir.resolve("site/restart.yml"), "# the second version\n");
        c2 = commit("second");
        repo = "file://" + originDir.toAbsolutePath();

        props = new RunnersProperties();
        props.setWorkDir(work.toString());
        props.setPath(bin + ":/usr/bin:/bin");
        props.setHeartbeat(Duration.ofMillis(200));
        props.getAnsible().setRunner(bin.resolve("ansible-runner").toString());
        props.getAnsible().setPlaybook(bin.resolve("ansible-playbook").toString());
        props.getAnsible().setGalaxy(bin.resolve("ansible-galaxy").toString());
        props.getAnsible().getEnv().put("ANSIBLE_FORKS", "7");
        PlaybooksProperties playbooks = new PlaybooksProperties();
        playbooks.getGit().setAllowFileUrls(true);
        playbooks.getGit().setCacheDir(tmp.resolve("git-cache").toString());
        CredentialsPort vault = mock(CredentialsPort.class);
        when(vault.resolve(anyString())).thenReturn(Mono.empty());
        when(vault.resolve("vault:ssh/ops")).thenReturn(Mono.just(KEY));
        ArtifactsProperties store = new ArtifactsProperties();
        store.setDir(tmp.resolve("artifacts").toString());
        artifacts = new FilesystemArtifactStore(store);
        providers = mock(ProviderQueries.class);
        when(providers.all()).thenReturn(Flux.empty());
        when(providers.provider(anyString())).thenReturn(Mono.empty());
        runner = new AnsibleRunnerAutomation(props, playbooks, new JGitRemotes(playbooks, vault), artifacts, vault, providers);
        events(AnsibleEventsTest.PASSING);
    }

    @AfterEach
    void close() {
        origin.close();
    }

    void fake(String name, String body) throws IOException {
        Path f = Files.writeString(bin.resolve(name), "#!/bin/bash\nR='" + record + "'\n" + body);
        f.toFile().setExecutable(true);
    }

    String commit(String message) throws Exception {
        origin.add().addFilepattern(".").call();
        return origin.commit().setMessage(message).setAuthor(WHO).setCommitter(WHO).setSign(false).call().name();
    }

    void events(List<String> lines) throws IOException {
        Files.write(record.resolve("events.jsonl"), lines);
    }

    RunRequest request(String withJson, String timeout, boolean dryRun, boolean checkMode) throws IOException {
        ObjectNode with = (ObjectNode) JSON.readTree(withJson.replace("REPO", repo).replace("C1", c1));
        Step step = new Step("patch", "Patch", StepType.ANSIBLE, with, null, null, null, timeout, true, null, null, null, null, null, null, null);
        return new RunRequest("job-9", 4, StepType.ANSIBLE, step, with, "test", dryRun, checkMode);
    }

    JsonNode run(String withJson) throws IOException {
        return runner.run(request(withJson, null, false, false), logged::add).block(Duration.ofSeconds(60));
    }

    List<String> argv() throws IOException {
        return Files.readAllLines(record.resolve("argv"));
    }

    static final String GIT = "{\"playbook\": {\"git\": {\"repo\": \"REPO\", \"ref\": \"v1\", \"path\": \"site/restart.yml\"}, \"commit\": \"C1\"}";

    @Test
    void aGitPlaybookRunsAtItsPinnedCommitWithItsInventoryVarsAndKey() throws Exception {
        JsonNode out = run(GIT + ", \"inventory\": {\"hosts\": [\"web1\", \"web2\"], \"group\": \"web\", \"vars\": {\"ansible_user\": \"ops\"}},"
                + " \"limit\": \"web1\", \"extraVars\": {\"vm_id\": \"vm-1\"}, \"credential\": \"vault:ssh/ops\"}");

        assertThat(out.path("changed").asInt()).isEqualTo(1);
        assertThat(out.path("rebootRequired").asBoolean()).isTrue();
        assertThat(out.path("hosts").path("localhost").path("ok").asInt()).isEqualTo(4);
        List<String> argv = argv();
        String pdd = argv.get(1);
        assertThat(argv).containsExactly("run", pdd, "-p", "site/restart.yml", "--json", "--inventory", pdd + "/inventory/hosts.json",
                "--limit", "web1", "--cmdline", "--private-key '" + pdd + "/.credentials/ssh_key'");
        Path seen = record.resolve("pdd");
        assertThat(Files.readString(seen.resolve("project/site/restart.yml"))).as("the pinned commit, not the branch head")
                .isEqualTo("- hosts: all\n  tasks: []\n");
        assertThat(JSON.readTree(seen.resolve("env/extravars").toFile())).isEqualTo(JSON.readTree("{\"vm_id\": \"vm-1\"}"));
        assertThat(JSON.readTree(seen.resolve("inventory/hosts.json").toFile())).isEqualTo(JSON.readTree(
                "{\"all\": {\"children\": {\"web\": {\"hosts\": {\"web1\": {}, \"web2\": {}}, \"vars\": {\"ansible_user\": \"ops\"}}}}}"));
        assertThat(Files.readString(seen.resolve(".credentials/ssh_key"))).isEqualTo(KEY + "\n");
        assertThat(Files.readString(record.resolve("key-mode")).strip()).isEqualTo("600");
        List<String> env = Files.readAllLines(record.resolve("env"));
        assertThat(env).contains("ANSIBLE_FORKS=7", "HOME=" + pdd + "/.home", "ANSIBLE_NOCOLOR=1")
                .anyMatch(l -> l.startsWith("PATH=" + bin + ":"))
                .noneMatch(l -> l.startsWith("JAVA_TOOL_OPTIONS="));
        assertThat(Path.of(pdd)).as("the private data directory, key included, is gone").doesNotExist();
        assertThat(logged).contains("PLAY [smoke]", "TASK [change something]", "changed: [localhost]",
                "localhost: ok=4 changed=1 unreachable=0 failed=0 skipped=0");
        assertThat(String.join("\n", logged)).doesNotContain("fake-ssh-key");
    }

    @Test
    void checkModeRunsTheCheckAndSaysSo() throws Exception {
        JsonNode out = runner.run(request(GIT + "}", null, false, true), logged::add).block(Duration.ofSeconds(60));

        assertThat(argv()).containsSubsequence("--cmdline", "--check");
        assertThat(out.path("checkMode").asBoolean()).isTrue();
    }

    @Test
    void anUnpinnedRefIsResolvedWhenTheStepRuns() throws Exception {
        run("{\"playbook\": {\"git\": {\"repo\": \"REPO\", \"ref\": \"main\", \"path\": \"site/restart.yml\"}}}");

        assertThat(Files.readString(record.resolve("pdd/project/site/restart.yml"))).isEqualTo("# the second version\n");
        assertThat(logged).anyMatch(l -> l.contains("not pinned") && l.contains(c2));
    }

    @Test
    void anUploadedArchiveIsUnpackedAndThePathInsideItRuns() throws Exception {
        Path zip = Archives.zip(tmp.resolve("bundle.zip"), Map.of("play/site.yml", "- hosts: all\n", "play/roles/x/tasks/main.yml", "- ping:\n"));
        String sha = artifacts.put(zip).block().sha256();

        run("{\"playbook\": {\"upload\": {\"file\": \"bundle.zip\", \"sha256\": \"" + sha + "\", \"path\": \"play/site.yml\"}}}");

        assertThat(argv()).containsSubsequence("-p", "play/site.yml");
        assertThat(record.resolve("pdd/project/play/roles/x/tasks/main.yml")).exists();
        assertThat(record.resolve("pdd/.upload/bundle.zip")).doesNotExist();
        assertThatThrownBy(() -> run("{\"playbook\": {\"upload\": {\"file\": \"bundle.zip\", \"sha256\": \"" + "0".repeat(64) + "\"}}}"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> assertThat(e.errorClass()).isEqualTo("not_found"));
    }

    @Test
    void aLibraryReferenceRunsTheProvidersPinnedSource() throws Exception {
        when(providers.provider("ops-playbooks")).thenReturn(Mono.just(ProviderYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Provider
                metadata: { name: ops-playbooks, owner: team-x, version: 1.0.0 }
                spec:
                  type: ansible
                  source: { git: { repo: "REPO", ref: v1 }, commit: C1 }
                  executionEnvironment: ee-platform:2.17
                  actions:
                    - id: vm.restart
                      playbook: site/restart.yml
                      effect: change
                """.replace("REPO", repo).replace("C1", c1))));

        run("{\"playbook\": \"ops-playbooks/vm.restart\", \"inventory\": \"inventories/prod.yml\"}");

        List<String> argv = argv();
        assertThat(argv).containsSubsequence("-p", "site/restart.yml").containsSubsequence("--inventory", argv.get(1) + "/project/inventories/prod.yml");
        assertThatThrownBy(() -> run("{\"playbook\": \"ops-playbooks/vm.unknown\"}"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> assertThat(e.errorClass()).isEqualTo("not_found"));
    }

    @Test
    void requirementsAreInstalledOncePerContentAndPutOnTheSearchPaths() throws Exception {
        Path originDir = tmp.resolve("origin");
        Files.writeString(originDir.resolve("requirements.yml"), "roles:\n  - name: acme.base\ncollections:\n  - name: acme.tools\n");
        String c3 = commit("requirements");
        String with = "{\"playbook\": {\"git\": {\"repo\": \"REPO\", \"ref\": \"main\", \"path\": \"site/restart.yml\"}, \"commit\": \"" + c3 + "\"}}";

        run(with);
        run(with);

        List<String> calls = Files.readAllLines(record.resolve("galaxy-calls"));
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).startsWith("role install -r requirements.yml -p ");
        assertThat(calls.get(1)).startsWith("collection install -r requirements.yml -p ");
        Path cache;
        try (Stream<Path> s = Files.list(work.resolve("ansible/requirements"))) {
            cache = s.filter(p -> !p.getFileName().toString().startsWith(".")).findFirst().orElseThrow();
        }
        assertThat(cache.resolve("collections/installed")).exists();
        assertThat(Files.readAllLines(record.resolve("env")))
                .contains("ANSIBLE_COLLECTIONS_PATH=" + cache.resolve("collections") + ":/usr/share/ansible/collections");
        assertThat(logged).anyMatch(l -> l.startsWith("requirements: installed before"));
    }

    @Test
    void aFailedHostFailsTheStepNamingTheHostAndTheTask() throws Exception {
        events(List.of(
                "{\"event\":\"playbook_on_task_start\",\"event_data\":{\"task\":\"break here\"}}",
                "{\"event\":\"runner_on_failed\",\"event_data\":{\"host\":\"web1\",\"task\":\"break here\",\"res\":{\"msg\":\"disk is full\"}}}",
                "{\"event\":\"playbook_on_stats\",\"event_data\":{\"ok\":{},\"dark\":{},\"failures\":{\"web1\":1},\"processed\":{\"web1\":1}}}"));
        Files.writeString(record.resolve("exit-code"), "2");

        assertThatThrownBy(() -> run(GIT + "}")).isInstanceOfSatisfying(ProviderCallException.class, e -> {
            assertThat(e.errorClass()).isEqualTo("failed");
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).isEqualTo("1 host failed: web1 (task 'break here': disk is full)");
        });
        assertThat(logged).contains("failed: [web1] disk is full");
    }

    @Test
    void anUnreachableHostFailsTheStepAsRetryable() throws Exception {
        events(List.of(
                "{\"event\":\"runner_on_unreachable\",\"event_data\":{\"host\":\"db1\",\"task\":\"ping it\",\"res\":{\"msg\":\"Connection refused\",\"unreachable\":true}}}",
                "{\"event\":\"playbook_on_stats\",\"event_data\":{\"ok\":{},\"dark\":{\"db1\":1},\"failures\":{},\"processed\":{\"db1\":1}}}"));
        Files.writeString(record.resolve("exit-code"), "4");

        assertThatThrownBy(() -> run(GIT + "}")).isInstanceOfSatisfying(ProviderCallException.class, e -> {
            assertThat(e.errorClass()).isEqualTo("unreachable");
            assertThat(e.retryable()).isTrue();
        });
    }

    @Test
    void aPlaybookPastItsTimeoutIsEnded() throws Exception {
        Files.writeString(record.resolve("sleep"), "30");
        long started = System.nanoTime();

        assertThatThrownBy(() -> runner.run(request(GIT + "}", "1s", false, false), logged::add).block(Duration.ofSeconds(60)))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.errorClass()).isEqualTo("timeout");
                    assertThat(e.getMessage()).isEqualTo("The playbook did not finish within 1s");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void withoutAnsibleRunnerItFallsBackToAnsiblePlaybookWithTheJsonCallback() throws Exception {
        props.getAnsible().setRunner(tmp.resolve("not-installed/ansible-runner").toString());
        Files.writeString(record.resolve("doc.json"), """
                {"custom_stats": {}, "global_custom_stats": {"rebootRequired": false},
                 "plays": [{"play": {"name": "restart"}, "tasks": [{"task": {"name": "reboot"}, "hosts": {"web1": {"changed": true}}}]}],
                 "stats": {"web1": {"changed": 1, "failures": 0, "ok": 2, "skipped": 0, "unreachable": 0}}}
                """);

        JsonNode out = runner.run(request(GIT + ", \"inventory\": [\"web1\"], \"extraVars\": {\"a\": 1}, \"credential\": \"vault:ssh/ops\"}",
                null, false, true), logged::add).block(Duration.ofSeconds(60));

        assertThat(out.path("ok").asInt()).isEqualTo(2);
        assertThat(out.path("rebootRequired").asBoolean()).isFalse();
        List<String> argv = Files.readAllLines(record.resolve("playbook-argv"));
        assertThat(argv.get(argv.size() - 1)).endsWith("/project/site/restart.yml");
        assertThat(argv).contains("--check", "--private-key").anyMatch(a -> a.startsWith("@") && a.endsWith("/env/extravars"))
                .anyMatch(a -> a.endsWith("/inventory/hosts.json"));
        assertThat(Files.readAllLines(record.resolve("playbook-env"))).contains("ANSIBLE_STDOUT_CALLBACK=ansible.posix.json", "ANSIBLE_SHOW_CUSTOM_STATS=1");
        assertThat(logged).contains("TASK [reboot]");
    }

    @Test
    void inAContainerThePlaybookRunsInTheStepsExecutionEnvironment() throws Exception {
        props.setContainerRuntime(bin.resolve("docker").toString());
        props.getAnsible().setNetwork("ops-net");

        JsonNode out = run(GIT + ", \"executionEnvironment\": \"ee-platform:2.17\", \"inventory\": [\"web1\"], \"credential\": \"vault:ssh/ops\"}");

        assertThat(out.path("rebootRequired").asBoolean()).isTrue();
        List<String> argv = Files.readAllLines(record.resolve("docker-argv"));
        assertThat(argv).containsSubsequence("run", "--rm")
                .containsSubsequence("--security-opt", "no-new-privileges")
                .containsSubsequence("--network", "ops-net")
                .containsSubsequence("-w", "/runner")
                .contains("ANSIBLE_FORKS=7", "HOME=/runner/.home")
                .containsSubsequence("ee-platform:2.17", "ansible-runner", "run", "/runner", "-p", "site/restart.yml", "--json",
                        "--inventory", "/runner/inventory/hosts.json", "--cmdline", "--private-key '/runner/.credentials/ssh_key'");
        assertThat(argv).anyMatch(a -> a.endsWith(":/runner")).anyMatch(a -> a.matches("\\d+:\\d+"));
        assertThatThrownBy(() -> run(GIT + "}")).as("a container needs an image")
                .isInstanceOfSatisfying(ProviderCallException.class, e -> assertThat(e.errorClass()).isEqualTo("misconfigured"));
    }

    @Test
    void aDynamicInventoryIsTheConfiguredOne() throws Exception {
        assertThatThrownBy(() -> run(GIT + ", \"inventory\": \"dynamic\"}"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> assertThat(e.getMessage()).contains("dynamic-inventory"));

        Path inv = Files.createDirectories(tmp.resolve("dynamic"));
        Files.writeString(inv.resolve("cloud.yml"), "plugin: example.cloud\n");
        props.getAnsible().setDynamicInventory(inv.toString());
        run(GIT + ", \"inventory\": \"dynamic\", \"limit\": [\"vm-1\", \"vm-2\"]}");

        List<String> argv = argv();
        assertThat(argv).containsSubsequence("--inventory", argv.get(1) + "/inventory").containsSubsequence("--limit", "vm-1,vm-2");
        assertThat(record.resolve("pdd/inventory/cloud.yml")).exists();
    }

    @Test
    void aPathOutsideTheSourceOrABadHostIsRefusedBeforeAnythingRuns() {
        assertThatThrownBy(() -> run("{\"playbook\": {\"git\": {\"repo\": \"REPO\", \"ref\": \"v1\", \"path\": \"../escape.yml\"}, \"commit\": \"C1\"}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("leaves its source");
        assertThatThrownBy(() -> run(GIT + ", \"inventory\": [\"web1 ansible_connection=local\"]}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is not a host name");
        assertThat(record.resolve("argv")).doesNotExist();
    }

    @Test
    void aDryRunAnswersFromTheDemoWorldWithoutRunningAnything() throws Exception {
        JsonNode out = runner.run(request(GIT + "}", null, true, false), logged::add).block(Duration.ofSeconds(10));

        assertThat(out).isEqualTo(JSON.readTree("{\"ok\": 1, \"changed\": 1, \"failed\": 0}"));
        assertThat(record.resolve("argv")).doesNotExist();
    }
}

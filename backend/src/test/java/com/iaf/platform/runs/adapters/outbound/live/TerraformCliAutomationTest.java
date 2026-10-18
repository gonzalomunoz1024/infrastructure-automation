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
import java.util.concurrent.CopyOnWriteArrayList;

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
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.RunRequest;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The terraform runner against a fake terraform that records each command, its environment and
 * the module it was handed, and prints what terraform 1.9 prints for it (the {@code -json} UI
 * stream, {@code show -json}, {@code output -json}). Modules come from a local git repository
 * through the real JGit remotes.
 */
class TerraformCliAutomationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final PersonIdent WHO = new PersonIdent("Test", "test@example.com");
    static final String BEARER = "brr-4471-not-a-real-value";
    static final List<String> PLAN = List.of(
            "{\"@level\":\"info\",\"@message\":\"Terraform 1.9.8\",\"type\":\"version\",\"terraform\":\"1.9.8\",\"ui\":\"1.2\"}",
            "{\"@level\":\"info\",\"@message\":\"terraform_data.lb: Plan to create\",\"type\":\"planned_change\"}",
            "{\"@level\":\"info\",\"@message\":\"terraform_data.pool[0]: Plan to create\",\"type\":\"planned_change\"}",
            "{\"@level\":\"info\",\"@message\":\"terraform_data.pool[1]: Plan to create\",\"type\":\"planned_change\"}",
            "{\"@level\":\"info\",\"@message\":\"Plan: 3 to add, 0 to change, 0 to destroy.\",\"type\":\"change_summary\"}",
            "{\"@level\":\"info\",\"@message\":\"Outputs: 2\",\"type\":\"outputs\"}");
    static final String SHOW = """
            {"format_version": "1.2", "terraform_version": "1.9.8", "resource_changes": [
              {"address": "terraform_data.lb", "mode": "managed", "type": "terraform_data", "change": {"actions": ["create"]}},
              {"address": "terraform_data.pool[0]", "mode": "managed", "type": "terraform_data", "change": {"actions": ["create"]}},
              {"address": "terraform_data.pool[1]", "mode": "managed", "type": "terraform_data", "change": {"actions": ["create"]}}]}
            """;
    static final List<String> APPLY = List.of(
            "{\"@level\":\"info\",\"@message\":\"terraform_data.lb: Creating...\",\"type\":\"apply_start\"}",
            "{\"@level\":\"info\",\"@message\":\"terraform_data.lb: Creation complete after 0s [id=5f0c]\",\"type\":\"apply_complete\"}",
            "{\"@level\":\"info\",\"@message\":\"Apply complete! Resources: 3 added, 0 changed, 0 destroyed.\",\"type\":\"change_summary\"}");
    static final String OUTPUT = """
            {"secret": {"sensitive": true, "type": "string", "value": "s3cr3t-web"},
             "url": {"sensitive": false, "type": "string", "value": "https://web.example.com"}}
            """;

    @TempDir
    Path tmp;
    Path bin;
    Path record;
    Path work;
    Git origin;
    String repo;
    String c1;
    RunnersProperties props;
    ArtifactStorePort artifacts;
    ProviderQueries providers;
    TerraformCliAutomation runner;
    final List<String> logged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        bin = Files.createDirectories(tmp.resolve("bin"));
        record = Files.createDirectories(tmp.resolve("record"));
        work = tmp.resolve("work");
        fake("terraform", """
                echo "$*" >> "$R/calls"
                pwd > "$R/cwd"
                env | sort > "$R/env"
                case "$1" in
                  init)
                    cp zz_platform_backend_override.tf "$R/backend.tf"
                    cp main.tf "$R/main.tf"
                    ls -A > "$R/module-files"
                    echo "Initializing the backend..."
                    if [ -n "$ACME_BEARER" ]; then echo "debug: the token is $ACME_BEARER" >&2; fi
                    if [ -f "$R/init-error" ]; then cat "$R/init-error" >&2; exit 1; fi
                    ;;
                  workspace) echo "Created and switched to workspace \\"$5\\"!" ;;
                  plan)
                    cp ../vars.tfvars.json "$R/vars.json" 2>/dev/null
                    for a in "$@"; do case "$a" in -out=*) echo "binary plan for $*" > "${a#-out=}" ;; esac; done
                    if [ -f "$R/sleep" ]; then sleep "$(cat "$R/sleep")"; fi
                    cat "$R/plan.jsonl"
                    exit "$(cat "$R/plan-exit" 2>/dev/null || echo 0)"
                    ;;
                  show) cat "$R/show.json" ;;
                  apply) cat "$R/apply.jsonl" ;;
                  output) cat "$R/output.json" ;;
                esac
                """);
        fake("docker", """
                printf '%s\\n' "$@" >> "$R/docker-argv"
                if [ "$1" = "rm" ]; then exit 0; fi
                host=""
                while [ $# -gt 0 ]; do
                  a="$1"; shift
                  case "$a" in
                    run|--rm|-i|--read-only) ;;
                    -v) case "$1" in *:/work) host="${1%:/work}" ;; esac; shift ;;
                    -w|-e|--*) shift ;;
                    *) break ;;
                  esac
                done
                cd "$host/module" || exit 9
                exec "$TF" "$@"
                """);
        Files.write(record.resolve("plan.jsonl"), PLAN);
        Files.writeString(record.resolve("show.json"), SHOW);
        Files.write(record.resolve("apply.jsonl"), APPLY);
        Files.writeString(record.resolve("output.json"), OUTPUT);

        Path originDir = tmp.resolve("origin");
        origin = Git.init().setDirectory(originDir.toFile()).setInitialBranch("main").call();
        Files.createDirectories(originDir.resolve("modules/lb"));
        Files.writeString(originDir.resolve("modules/lb/main.tf"), "# lb 2.3.0\n");
        Files.writeString(originDir.resolve("README.md"), "modules\n");
        c1 = commit("lb 2.3.0");
        origin.tag().setName("v2.3.0").setAnnotated(false).call();
        Files.writeString(originDir.resolve("modules/lb/main.tf"), "# lb next\n");
        Files.writeString(originDir.resolve("modules/lb/extra.tf"), "# not released yet\n");
        commit("lb next");
        repo = "file://" + originDir.toAbsolutePath();

        props = new RunnersProperties();
        props.setWorkDir(work.toString());
        props.setPath(bin + ":/usr/bin:/bin");
        props.setHeartbeat(Duration.ofMillis(200));
        props.getTerraform().setBinary(bin.resolve("terraform").toString());
        PlaybooksProperties git = new PlaybooksProperties();
        git.getGit().setAllowFileUrls(true);
        git.getGit().setCacheDir(tmp.resolve("git-cache").toString());
        CredentialsPort vault = mock(CredentialsPort.class);
        when(vault.resolve(anyString())).thenReturn(Mono.empty());
        when(vault.resolve("vault:cloud/acme")).thenReturn(Mono.just("{\"ACME_BEARER\": \"" + BEARER + "\", \"ACME_REGION\": \"eu-1\"}"));
        when(vault.resolve("vault:cloud/lines")).thenReturn(Mono.just("# acme\nexport ACME_BEARER=\"" + BEARER + "\"\n"));
        when(vault.resolve("vault:cloud/sneaky")).thenReturn(Mono.just("TF_CLI_ARGS=-auto-approve\n"));
        ArtifactsProperties store = new ArtifactsProperties();
        store.setDir(tmp.resolve("artifacts").toString());
        artifacts = new FilesystemArtifactStore(store);
        providers = mock(ProviderQueries.class);
        when(providers.all()).thenReturn(Flux.empty());
        runner = new TerraformCliAutomation(props, new JGitRemotes(git, vault), artifacts, vault, providers);
    }

    @AfterEach
    void close() {
        origin.close();
    }

    void fake(String name, String body) throws IOException {
        Path f = Files.writeString(bin.resolve(name), "#!/bin/bash\nR='" + record + "'\nTF='" + bin.resolve("terraform") + "'\n" + body);
        f.toFile().setExecutable(true);
    }

    String commit(String message) throws Exception {
        origin.add().addFilepattern(".").call();
        return origin.commit().setMessage(message).setAuthor(WHO).setCommitter(WHO).setSign(false).call().name();
    }

    RunRequest request(String withJson, String timeout, boolean dryRun, boolean checkMode) throws IOException {
        ObjectNode with = (ObjectNode) JSON.readTree(withJson.replace("REPO", repo));
        Step step = new Step("lb", "Load balancer", StepType.TERRAFORM, with, null, null, null, timeout, true, null, null, null, null, null, null, null);
        return new RunRequest("job-7", 2, StepType.TERRAFORM, step, with, "dev", dryRun, checkMode);
    }

    JsonNode run(String withJson) throws IOException {
        return run(withJson, false);
    }

    JsonNode run(String withJson, boolean checkMode) throws IOException {
        return runner.run(request(withJson, null, false, checkMode), logged::add).block(Duration.ofSeconds(60));
    }

    List<String> calls() throws IOException {
        return Files.readAllLines(record.resolve("calls"));
    }

    String stored(String sha) throws IOException {
        return Files.readString(artifacts.get(sha, tmp.resolve("fetched-" + sha)).block());
    }

    static final String MODULE = "\"module\": {\"source\": \"git::REPO//modules/lb\", \"version\": \"2.3.0\"}";

    @Test
    void applyRunsThePinnedModuleAndAppliesTheSavedPlan() throws Exception {
        JsonNode out = run("{\"action\": \"apply\", " + MODULE + ", \"vars\": {\"name\": \"web\", \"targets\": [\"a\", \"b\"]},"
                + " \"credential\": \"vault:cloud/acme\"}");

        assertThat(calls()).containsExactly(
                "init -input=false -no-color",
                "workspace select -or-create=true -no-color job-7-lb",
                "plan -input=false -no-color -json -lock-timeout=5m -out=../tfplan -var-file=../vars.tfvars.json",
                "show -json -no-color ../tfplan",
                "apply -input=false -no-color -json -lock-timeout=5m ../tfplan",
                "output -json -no-color");
        assertThat(Files.readString(record.resolve("main.tf"))).as("the tagged commit, not the branch head").isEqualTo("# lb 2.3.0\n");
        assertThat(Files.readAllLines(record.resolve("module-files"))).containsExactlyInAnyOrder("main.tf", "zz_platform_backend_override.tf");
        assertThat(JSON.readTree(record.resolve("vars.json").toFile())).isEqualTo(JSON.readTree("{\"name\": \"web\", \"targets\": [\"a\", \"b\"]}"));
        Path state = work.resolve("terraform/state");
        assertThat(Files.readString(record.resolve("backend.tf"))).isEqualTo("terraform {\n  backend \"local\" {\n"
                + "    path          = \"" + state.resolve("default.tfstate") + "\"\n"
                + "    workspace_dir = \"" + state.resolve("workspaces") + "\"\n  }\n}\n");
        Path dir = Path.of(Files.readString(record.resolve("cwd")).strip()).getParent();
        assertThat(Files.readAllLines(record.resolve("env")))
                .contains("ACME_BEARER=" + BEARER, "ACME_REGION=eu-1", "TF_IN_AUTOMATION=1", "TF_INPUT=0", "CHECKPOINT_DISABLE=1",
                        "HOME=" + dir.resolve(".home"), "TF_PLUGIN_CACHE_DIR=" + work.resolve("terraform/plugin-cache"))
                .anyMatch(l -> l.startsWith("PATH=" + bin + ":"))
                .noneMatch(l -> l.startsWith("JAVA_TOOL_OPTIONS="));
        assertThat(dir).as("the working directory of the run is gone").doesNotExist();

        assertThat(out.path("add").asInt()).isEqualTo(3);
        assertThat(out.path("change").asInt()).isZero();
        assertThat(out.path("destroy").asInt()).isZero();
        assertThat(out.path("workspace").asText()).isEqualTo("job-7-lb");
        assertThat(out.path("commit").asText()).isEqualTo(c1);
        assertThat(out.path("outputs")).isEqualTo(JSON.readTree("{\"secret\": \"(sensitive)\", \"url\": \"https://web.example.com\"}"));
        String planSha = out.path("plan").path("sha256").asText();
        assertThat(stored(planSha)).startsWith("binary plan for plan -input=false");
        assertThat(JSON.readTree(stored(out.path("plan").path("json").asText()))).isEqualTo(JSON.readTree(SHOW));
        assertThat(out.path("plan").path("size").asLong()).isPositive();

        assertThat(logged).contains("module: " + repo + "//modules/lb at " + c1.substring(0, 12), "terraform init",
                "terraform_data.lb: Plan to create", "Plan: 3 to add, 0 to change, 0 to destroy.",
                "plan: 3 to add, 0 to change, 0 to destroy (kept as " + planSha.substring(0, 12) + ")",
                "terraform_data.lb: Creation complete after 0s [id=5f0c]", "Apply complete! Resources: 3 added, 0 changed, 0 destroyed.",
                "apply complete", "debug: the token is ****");
        assertThat(String.join("\n", logged)).doesNotContain(BEARER).doesNotContain("s3cr3t-web").doesNotContain("Terraform 1.9.8");
        assertThat(out.toString()).doesNotContain(BEARER).doesNotContain("s3cr3t-web");
    }

    @Test
    void checkModeStopsAfterThePlan() throws Exception {
        JsonNode out = run("{\"action\": \"apply\", " + MODULE + "}", true);

        assertThat(calls()).noneMatch(c -> c.startsWith("apply") || c.startsWith("output"))
                .contains("plan -input=false -no-color -json -lock-timeout=5m -out=../tfplan");
        assertThat(out.path("checkMode").asBoolean()).isTrue();
        assertThat(out.has("outputs")).isFalse();
        assertThat(out.path("add").asInt()).isEqualTo(3);
    }

    @Test
    void destroyPlansTheDestructionInTheNamedWorkspaceAndAppliesIt() throws Exception {
        Files.writeString(record.resolve("show.json"), SHOW.replace("\"create\"", "\"delete\""));
        Files.writeString(record.resolve("output.json"), "{}\n");

        JsonNode out = run("{\"action\": \"destroy\", \"workspace\": \"shared/web 1\", \"credential\": \"vault:cloud/lines\","
                + " \"module\": {\"source\": \"git::REPO//modules/lb?ref=v2.3.0\"}}");

        assertThat(calls()).contains("workspace select -or-create=true -no-color shared-web-1",
                "plan -input=false -no-color -json -lock-timeout=5m -out=../tfplan -destroy",
                "apply -input=false -no-color -json -lock-timeout=5m ../tfplan");
        assertThat(Files.readAllLines(record.resolve("env"))).contains("ACME_BEARER=" + BEARER);
        assertThat(out.path("destroy").asInt()).isEqualTo(3);
        assertThat(out.path("add").asInt()).isZero();
        assertThat(out.path("workspace").asText()).isEqualTo("shared-web-1");
        assertThat(out.path("outputs").isEmpty()).isTrue();
        assertThat(logged).contains("destroy complete");
    }

    @Test
    void aModuleMustBeAPinnedGitSourceAndTheCredentialMayNotSteerTerraform() {
        assertThatThrownBy(() -> run("{\"action\": \"plan\", \"module\": {\"source\": \"git::REPO//modules/lb\"}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not pinned");
        assertThatThrownBy(() -> run("{\"action\": \"plan\", \"module\": {\"source\": \"acme/lb/aws\", \"version\": \"2.3.0\"}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Registry module sources");
        assertThatThrownBy(() -> run("{\"action\": \"plan\", \"module\": {\"source\": \"git::REPO//modules/lb\", \"version\": \"9.9.9\"}}"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.errorClass()).isEqualTo("not_found");
                    assertThat(e.getMessage()).contains("9.9.9 or v9.9.9");
                });
        assertThatThrownBy(() -> run("{\"action\": \"plan\", \"module\": {\"source\": \"git::REPO//modules/none\", \"version\": \"2.3.0\"}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no directory modules/none");
        assertThatThrownBy(() -> run("{\"action\": \"plan\", " + MODULE + ", \"credential\": \"vault:cloud/sneaky\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("may not set TF_CLI_ARGS");
        assertThatThrownBy(() -> run("{\"action\": \"import\", " + MODULE + "}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plan, apply or destroy");
        assertThat(record.resolve("calls")).as("terraform never ran").doesNotExist();
    }

    @Test
    void aFailedPlanFailsTheStepWithTerraformsErrorDiagnostic() throws Exception {
        Files.write(record.resolve("plan.jsonl"), List.of(PLAN.get(0),
                "{\"@level\":\"error\",\"@message\":\"Error: Unclosed configuration block\",\"type\":\"diagnostic\",\"diagnostic\":"
                        + "{\"severity\":\"error\",\"summary\":\"Unclosed configuration block\",\"detail\":\"There is no closing brace for this block"
                        + " before the end of the file.\"}}"));
        Files.writeString(record.resolve("plan-exit"), "1");

        assertThatThrownBy(() -> run("{\"action\": \"apply\", " + MODULE + "}")).isInstanceOfSatisfying(ProviderCallException.class, e -> {
            assertThat(e.errorClass()).isEqualTo("failed");
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).isEqualTo("terraform plan failed: Unclosed configuration block: There is no closing brace for this block"
                    + " before the end of the file.");
        });
        assertThat(logged).contains("Error: Unclosed configuration block");
        assertThat(calls()).noneMatch(c -> c.startsWith("apply"));
    }

    @Test
    void aFailedInitIsRetryableAndItsMessageHidesTheCredential() throws Exception {
        Files.writeString(record.resolve("init-error"), "Error: Failed to query available provider packages for " + BEARER + "\n");

        assertThatThrownBy(() -> run("{\"action\": \"plan\", " + MODULE + ", \"credential\": \"vault:cloud/acme\"}"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.errorClass()).isEqualTo("failed");
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).isEqualTo("terraform init failed: Failed to query available provider packages for ****");
                });
    }

    @Test
    void aBrokenConfigurationAtInitIsNotRetried() throws Exception {
        Files.writeString(record.resolve("init-error"), """

                Error: Terraform encountered problems during initialisation, including problems
                with the configuration, described below.

                Error: Unclosed configuration block

                  on main.tf line 1, in resource "nope" "x":
                """);

        assertThatThrownBy(() -> run("{\"action\": \"plan\", " + MODULE + "}")).isInstanceOfSatisfying(ProviderCallException.class, e -> {
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).isEqualTo("terraform init failed: Unclosed configuration block");
        });
    }

    @Test
    void aRunPastItsTimeoutIsEnded() throws Exception {
        Files.writeString(record.resolve("sleep"), "30");
        long started = System.nanoTime();

        assertThatThrownBy(() -> runner.run(request("{\"action\": \"plan\", " + MODULE + "}", "3s", false, false), logged::add)
                .block(Duration.ofSeconds(60))).isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.errorClass()).isEqualTo("timeout");
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).endsWith(" did not finish within 3s");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void aConfiguredBackendIsDeclaredAndConfiguredAtInit() throws Exception {
        props.getTerraform().setBackend("s3");
        props.getTerraform().getBackendConfig().put("bucket", "tf-state");
        props.getTerraform().getBackendConfig().put("region", "eu-west-1");

        run("{\"action\": \"plan\", " + MODULE + "}");

        assertThat(calls().get(0)).isEqualTo("init -input=false -no-color -backend-config=bucket=tf-state -backend-config=region=eu-west-1");
        assertThat(Files.readString(record.resolve("backend.tf"))).isEqualTo("terraform {\n  backend \"s3\" {}\n}\n");

        props.getTerraform().setBackend("s3\" {}\nresource \"x\" \"y\" {");
        assertThatThrownBy(() -> run("{\"action\": \"plan\", " + MODULE + "}"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> assertThat(e.errorClass()).isEqualTo("misconfigured"));
    }

    @Test
    void inAContainerTerraformRunsInTheConfiguredImageWithSecretsByName() throws Exception {
        props.setContainerRuntime(bin.resolve("docker").toString());

        JsonNode out = run("{\"action\": \"apply\", " + MODULE + ", \"vars\": {\"name\": \"web\"}, \"credential\": \"vault:cloud/acme\"}");

        assertThat(out.path("outputs").path("url").asText()).isEqualTo("https://web.example.com");
        List<String> argv = Files.readAllLines(record.resolve("docker-argv"));
        assertThat(argv).containsSubsequence("run", "--rm")
                .containsSubsequence("--security-opt", "no-new-privileges")
                .containsSubsequence("--cap-drop", "ALL")
                .containsSubsequence("-w", "/work/module")
                .containsSubsequence("-v", work.resolve("terraform/state") + ":/state")
                .containsSubsequence("-v", work.resolve("terraform/plugin-cache") + ":/plugin-cache")
                .containsSubsequence("-e", "TF_IN_AUTOMATION=1")
                .containsSubsequence("-e", "HOME=/work/.home")
                .containsSubsequence("-e", "TF_PLUGIN_CACHE_DIR=/plugin-cache")
                .containsSubsequence("-e", "ACME_BEARER")
                .containsSubsequence("hashicorp/terraform:1.9.8", "apply", "-input=false")
                .anyMatch(a -> a.endsWith(":/work"))
                .noneMatch(a -> a.contains(BEARER));
        assertThat(Files.readString(record.resolve("backend.tf"))).contains("path          = \"/state/default.tfstate\"",
                "workspace_dir = \"/state/workspaces\"");
    }

    @Test
    void aDryRunAnswersFromTheDemoWorldWithoutRunningAnything() throws Exception {
        JsonNode out = runner.run(request("{\"action\": \"apply\", " + MODULE + "}", null, true, false), logged::add).block(Duration.ofSeconds(10));

        assertThat(out).isEqualTo(JSON.readTree("{\"add\": 0, \"change\": 0, \"destroy\": 0, \"outputs\": {}}"));
        assertThat(record.resolve("calls")).doesNotExist();
    }

    @Test
    void workspacesAreNamedPerJobAndStepWithinTerraformsCharacters() throws Exception {
        RunRequest r = request("{\"action\": \"plan\", " + MODULE + "}", null, false, false);

        assertThat(TerraformCliAutomation.workspace(null, r)).isEqualTo("job-7-lb");
        assertThat(TerraformCliAutomation.workspace("prod/eu west", r)).isEqualTo("prod-eu-west");
        assertThat(TerraformCliAutomation.workspace("w".repeat(120), r)).hasSize(90);
        assertThat(TerraformCliAutomation.hcl("C:\\state\\\"${x}\"")).isEqualTo("C:\\\\state\\\\\\\"$${x}\\\"");
    }
}

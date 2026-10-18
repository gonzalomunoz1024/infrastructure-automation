package com.iaf.platform.runs.adapters.outbound.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
 * A real terraform on a module of built-in {@code terraform_data} resources (no provider to
 * download), run only when {@code IAF_TERRAFORM_BIN} names a terraform 1.4+ executable.
 */
@EnabledIfEnvironmentVariable(named = "IAF_TERRAFORM_BIN", matches = ".+")
class TerraformRealRunTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final PersonIdent WHO = new PersonIdent("Test", "test@example.com");
    static final String MODULE = """
            variable "name" { type = string }
            variable "targets" { type = list(string) }
            resource "terraform_data" "lb" {
              input = { name = var.name, targets = var.targets }
            }
            resource "terraform_data" "pool" {
              count = length(var.targets)
              input = var.targets[count.index]
            }
            output "url" { value = "https://${var.name}.example.com" }
            output "secret" {
              value     = "s3cr3t-${var.name}"
              sensitive = true
            }
            """;

    @TempDir
    Path tmp;
    Path work;
    String repo;
    ArtifactStorePort artifacts;
    TerraformCliAutomation runner;
    final List<String> logged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path originDir = tmp.resolve("origin");
        try (Git origin = Git.init().setDirectory(originDir.toFile()).setInitialBranch("main").call()) {
            Files.createDirectories(originDir.resolve("modules/lb"));
            Files.writeString(originDir.resolve("modules/lb/main.tf"), MODULE);
            Files.createDirectories(originDir.resolve("modules/broken"));
            Files.writeString(originDir.resolve("modules/broken/main.tf"), "resource \"nope\" \"x\" {\n");
            origin.add().addFilepattern(".").call();
            origin.commit().setMessage("modules").setAuthor(WHO).setCommitter(WHO).setSign(false).call();
            origin.tag().setName("v1.0.0").setAnnotated(false).call();
        }
        repo = "file://" + originDir.toAbsolutePath();
        work = tmp.resolve("work");

        RunnersProperties props = new RunnersProperties();
        props.setWorkDir(work.toString());
        props.getTerraform().setBinary(System.getenv("IAF_TERRAFORM_BIN"));
        PlaybooksProperties git = new PlaybooksProperties();
        git.getGit().setAllowFileUrls(true);
        git.getGit().setCacheDir(tmp.resolve("git-cache").toString());
        CredentialsPort vault = mock(CredentialsPort.class);
        when(vault.resolve(anyString())).thenReturn(Mono.empty());
        ArtifactsProperties store = new ArtifactsProperties();
        store.setDir(tmp.resolve("artifacts").toString());
        artifacts = new FilesystemArtifactStore(store);
        ProviderQueries providers = mock(ProviderQueries.class);
        when(providers.all()).thenReturn(Flux.empty());
        runner = new TerraformCliAutomation(props, new JGitRemotes(git, vault), artifacts, vault, providers);
    }

    JsonNode run(String action, String dir) throws Exception {
        ObjectNode with = (ObjectNode) JSON.readTree("""
                {"action": "ACTION", "workspace": "real-ws",
                 "module": {"source": "git::REPO//modules/DIR", "version": "1.0.0"},
                 "vars": {"name": "web", "targets": ["a", "b"]}}
                """.replace("ACTION", action).replace("REPO", repo).replace("DIR", dir));
        Step step = new Step("lb", "Load balancer", StepType.TERRAFORM, with, null, null, null, "5m", true, null, null, null, null, null, null, null);
        return runner.run(new RunRequest("job-real", 1, StepType.TERRAFORM, step, with, "dev", false, false), logged::add)
                .block(Duration.ofMinutes(5));
    }

    @Test
    void aRealModuleIsPlannedAppliedAndDestroyedInItsWorkspace() throws Exception {
        JsonNode applied = run("apply", "lb");

        assertThat(applied.path("add").asInt()).isEqualTo(3);
        assertThat(applied.path("outputs")).isEqualTo(JSON.readTree("{\"secret\": \"(sensitive)\", \"url\": \"https://web.example.com\"}"));
        assertThat(work.resolve("terraform/state/workspaces/real-ws/terraform.tfstate")).exists();
        Path planJson = artifacts.get(applied.path("plan").path("json").asText(), tmp.resolve("plan.json")).block();
        assertThat(JSON.readTree(planJson.toFile()).path("resource_changes")).hasSize(3);
        assertThat(logged).contains("Plan: 3 to add, 0 to change, 0 to destroy.", "Apply complete! Resources: 3 added, 0 changed, 0 destroyed.");
        assertThat(String.join("\n", logged)).doesNotContain("s3cr3t-web");

        JsonNode again = run("plan", "lb");
        assertThat(again.path("add").asInt() + again.path("change").asInt() + again.path("destroy").asInt()).as("nothing left to do").isZero();

        JsonNode destroyed = run("destroy", "lb");
        assertThat(destroyed.path("destroy").asInt()).isEqualTo(3);
        assertThat(destroyed.path("outputs").isEmpty()).isTrue();
    }

    @Test
    void aBrokenModuleFailsAtInitWithTerraformsDiagnostic() {
        assertThatThrownBy(() -> run("plan", "broken")).isInstanceOfSatisfying(ProviderCallException.class, e -> {
            assertThat(e.errorClass()).isEqualTo("failed");
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).isEqualTo("terraform init failed: Unclosed configuration block");
        });
    }
}

package com.iaf.platform.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.providers.adapters.outbound.JGitSource;
import com.iaf.platform.providers.domain.AnsibleImport;
import com.iaf.platform.providers.domain.ProviderChecks;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.GitRef;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.domain.TerraformImport;
import com.iaf.platform.providers.domain.TerraformModule;
import com.iaf.platform.providers.ports.inbound.ProviderImports.ImportAnsible;
import com.iaf.platform.providers.ports.inbound.ProviderImports.ImportTerraform;
import com.iaf.platform.providers.ports.inbound.ProviderImports.Registry;
import com.iaf.platform.providers.ports.outbound.ModuleRegistryPort;
import com.iaf.platform.providers.ports.outbound.ModuleRegistryPort.Location;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Imports read real repositories on disk at a ref and answer a draft provider pinned to what was read. */
class ProviderImportHandlerTest {

    static final PersonIdent SEED = new PersonIdent("seed", "seed@example.com");

    static final String RESTART = """
            # Restarts one VM.
            - name: Restart a VM
              hosts: "{{ target }}"
              roles:
                - vm_power
            """;
    static final String FCV = """
            - hosts: mongo
              tasks:
                - ansible.builtin.command: "mongosh --eval 'setFCV({{ fcv }})'"
            """;
    static final String VARIABLES = """
            variable "name" {
              type        = string
              description = "What to call the load balancer"
            }
            variable "targets" {
              type    = list(string)
              default = []
            }
            variable "admin_password" {
              type      = string
              sensitive = true
            }
            """;

    @TempDir
    Path dir;
    String playbooks;
    String modules;
    String v1;
    String tfCommit;
    ProviderStorePort store;
    ModuleRegistryPort registry;
    ProviderImportHandler handler;

    @BeforeEach
    void seedRepositories() throws Exception {
        Path ansible = dir.resolve("ops-playbooks");
        playbooks = ansible.toString();
        try (Git g = Git.init().setDirectory(ansible.toFile()).setInitialBranch("main").call()) {
            write(ansible, "vm/restart.yml", RESTART);
            write(ansible, "mongo/set-fcv.yml", FCV);
            write(ansible, "roles/vm_power/defaults/main.yml", "graceful: true\n");
            write(ansible, "roles/vm_power/tasks/main.yml", "- ansible.builtin.debug: { msg: \"{{ vm_id }}\" }\n");
            write(ansible, "requirements.yml", "collections: [community.vmware]\n");
            g.add().addFilepattern(".").call();
            v1 = g.commit().setMessage("v1").setAuthor(SEED).setCommitter(SEED).setSign(false).call().name();
            g.tag().setName("v1.0.0").setAnnotated(true).setMessage("v1.0.0").setTagger(SEED).setSigned(false).call();
            write(ansible, "decommission-vm.yml", "- hosts: all\n  tasks: []\n");
            g.add().addFilepattern(".").call();
            g.commit().setMessage("later").setAuthor(SEED).setCommitter(SEED).setSign(false).call();
        }
        Path terraform = dir.resolve("tf-modules");
        modules = terraform.toString();
        try (Git g = Git.init().setDirectory(terraform.toFile()).setInitialBranch("main").call()) {
            write(terraform, "modules/lb/variables.tf", VARIABLES);
            write(terraform, "modules/lb/main.tf", "resource \"aws_lb\" \"this\" {\n  name = var.name\n}\n");
            write(terraform, "modules/lb/outputs.tf", "output \"url\" {\n  value = aws_lb.this.dns_name\n}\n");
            write(terraform, "modules/net/main.tf", "variable \"cidr\" {}\n");
            write(terraform, "README.md", "# modules\n");
            g.add().addFilepattern(".").call();
            tfCommit = g.commit().setMessage("lb").setAuthor(SEED).setCommitter(SEED).setSign(false).call().name();
            g.tag().setName("v2.3.0").setAnnotated(true).setMessage("v2.3.0").setTagger(SEED).setSigned(false).call();
        }
        store = mock(ProviderStorePort.class);
        registry = mock(ModuleRegistryPort.class);
        when(registry.host()).thenReturn("registry.terraform.io");
        handler = new ProviderImportHandler(new JGitSource(mock(CredentialsPort.class), true, Duration.ofSeconds(30)), registry, store);
    }

    static void write(Path root, String path, String text) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    static JsonNode json(String yaml) {
        return WorkflowYaml.readTree(yaml);
    }

    // ---------------------------------------------------------------- ansible

    @Test
    void anAnsibleRepositoryBecomesADraftProviderPinnedToTheCommitTheRefPointsAt() {
        ImportAnsible command = new ImportAnsible(new GitRef(playbooks, "v1.0.0", null, null), null, null, null, null, null);
        AnsibleImport result = handler.importAnsible(command).block();
        assertThat(result.commit()).isEqualTo(v1);
        assertThat(result.ref()).isEqualTo("v1.0.0");
        assertThat(result.playbooks()).extracting(p -> p.path()).containsExactly("mongo/set-fcv.yml", "vm/restart.yml");
        assertThat(result.chosen()).containsExactly("mongo.setFcv", "vm.restart");
        assertThat(result.requirements()).containsExactly("requirements.yml");
        ProviderDefinition def = ProviderYaml.parse(result.yaml());
        assertThat(def.name()).isEqualTo("ops-playbooks");
        assertThat(def.title()).isEqualTo("Ops playbooks (Ansible)");
        assertThat(def.owner()).isEqualTo("your-team");
        assertThat(def.metadata().version()).isEqualTo("1.0.0");
        assertThat(def.spec().source()).isEqualTo(json("{ git: { repo: '" + playbooks + "', ref: v1.0.0 }, commit: '" + v1 + "' }"));
        assertThat(def.action("vm.restart").orElseThrow().vars()).isEqualTo(json("""
                type: object
                required: [vm_id, target]
                properties:
                  vm_id: { type: string }
                  target: { type: string }
                  graceful: { type: boolean, default: true }
                """));
        assertThat(ProviderChecks.check(result.yaml()).errors()).isEmpty();
        assertThat(result.notes()).contains(
                "The playbooks are pinned to commit " + v1.substring(0, 12) + " of v1.0.0: a later push changes nothing until a new version of the provider is published.",
                "Set metadata.owner to the team that owns the provider.",
                "Set auth.credential to the Vault reference of the machine credential the playbooks connect with, like vault:ssh/automation.",
                "Set executionEnvironment to the image that runs the playbooks: Ansible and the collections in their requirements.yml.",
                "Variables a playbook uses without giving them a value became required string inputs: drop the ones your inventory sets and check their types.");
    }

    @Test
    void withoutARefTheMainBranchIsReadAndTheCredentialAndImageGoInTheFile() {
        ImportAnsible command = new ImportAnsible(new GitRef(playbooks, null, null, "vault:github/read-token"), null, "vm-ops", "team-compute",
                "vault:ssh/automation", "ee-platform:2.17");
        CredentialsPort credentials = mock(CredentialsPort.class);
        when(credentials.resolve("vault:github/read-token")).thenReturn(Mono.just("ghp-not-needed-on-disk"));
        handler = new ProviderImportHandler(new JGitSource(credentials, true, Duration.ofSeconds(30)), registry, store);
        AnsibleImport result = handler.importAnsible(command).block();
        assertThat(result.ref()).isEqualTo("main");
        assertThat(result.chosen()).containsExactly("vm.decommission", "mongo.setFcv", "vm.restart");
        ProviderDefinition def = ProviderYaml.parse(result.yaml());
        assertThat(def.name()).isEqualTo("vm-ops");
        assertThat(def.owner()).isEqualTo("team-compute");
        assertThat(def.spec().source().path("git").path("credential").asText()).isEqualTo("vault:github/read-token");
        assertThat(def.spec().auth().credential()).isEqualTo("vault:ssh/automation");
        assertThat(def.spec().executionEnvironment()).isEqualTo("ee-platform:2.17");
        assertThat(def.action("vm.decommission").orElseThrow().approvalRequired()).isTrue();
        assertThat(result.yaml()).doesNotContain("ghp-not-needed-on-disk");
        assertThat(result.notes()).noneMatch(n -> n.startsWith("Set metadata.owner") || n.startsWith("Set auth.credential") || n.startsWith("Set executionEnvironment"));
    }

    @Test
    void onlyThePlaybooksAskedForBecomeActionsAndAnUnknownOneIsNamed() {
        ImportAnsible command = new ImportAnsible(new GitRef(playbooks, "v1.0.0", null, null), List.of("vm/restart.yml", "mongo.setFcv"), null, null, null, null);
        AnsibleImport result = handler.importAnsible(command).block();
        assertThat(result.chosen()).containsExactly("mongo.setFcv", "vm.restart");
        StepVerifier.create(handler.importAnsible(new ImportAnsible(new GitRef(playbooks, "v1.0.0", null, null), List.of("vm/restart.yml"), null, null, null, null)))
                .assertNext(r -> assertThat(ProviderYaml.parse(r.yaml()).actions()).extracting(ProviderDefinition.Action::id).containsExactly("vm.restart"))
                .verifyComplete();
        StepVerifier.create(handler.importAnsible(new ImportAnsible(new GitRef(playbooks, "v1.0.0", null, null), List.of("nope.yml"), null, null, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("No playbook nope.yml in the repository; its playbooks are mongo/set-fcv.yml, vm/restart.yml"))
                .verify();
    }

    @Test
    void aFolderWithoutPlaybooksSaysSo() {
        StepVerifier.create(handler.importAnsible(new ImportAnsible(new GitRef(playbooks, "v1.0.0", "playbooks", null), null, null, null, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("No playbooks in " + playbooks + " at v1.0.0 under playbooks: a playbook is a YAML list of plays, each with hosts"))
                .verify();
    }

    @Test
    void aSecretNeverGoesThroughTheImportOnlyItsVaultReference() {
        StepVerifier.create(handler.importAnsible(new ImportAnsible(new GitRef(playbooks, null, null, null), null, null, null, "hunter2", null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageStartingWith("credential is a Vault reference").hasMessageNotContaining("hunter2"))
                .verify();
        StepVerifier.create(handler.importAnsible(new ImportAnsible(new GitRef(playbooks, null, null, "ghp_abc123"), null, null, null, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageStartingWith("git.credential is a Vault reference").hasMessageNotContaining("ghp_abc123"))
                .verify();
        StepVerifier.create(handler.importTerraform(new ImportTerraform(null, new Registry("acme/lb/aws", "2.3.0", "tfr-token"), null, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageStartingWith("registry.credential is a Vault reference").hasMessageNotContaining("tfr-token"))
                .verify();
        verify(registry, never()).locate(anyString(), anyString(), any());
    }

    @Test
    void anExistingProviderGetsTheNextMinorVersionAndANoteSayingWhoMayPublishIt() {
        ProviderDefinition existing = ProviderYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Provider
                metadata: { name: ops-playbooks, owner: team-platform, version: 3.1.0 }
                spec:
                  type: ansible
                  source: { git: { repo: github.com/acme/ops-playbooks, ref: v3.1.0 }, commit: 4e1d9a2 }
                  actions: [{ id: vm.restart, playbook: vm/restart.yml, effect: change }]
                """);
        when(store.versions("ops-playbooks")).thenReturn(List.of(new StoredProvider(existing, "", "providers/team-platform/ops-playbooks.yaml",
                Instant.parse("2026-10-01T00:00:00Z"), "ana")));
        AnsibleImport result = handler.importAnsible(new ImportAnsible(new GitRef(playbooks, "v1.0.0", null, null), null, null, "team-compute", null, null))
                .block();
        assertThat(ProviderYaml.parse(result.yaml()).metadata().version()).isEqualTo("3.2.0");
        assertThat(result.notes()).contains("Provider ops-playbooks already exists (version 3.1.0, owned by team-platform): this draft is version 3.2.0 "
                + "and replaces its actions when its change request is merged; only team-platform may publish it.");
    }

    @Test
    void theOwnerIsTheCallersTeamWhenTheyAreInOne() {
        Identity ana = new Identity("ana", "Ana", null, List.of("team-db"), List.of("engineer"), false, Identity.Kind.USER);
        AnsibleImport result = handler.importAnsible(new ImportAnsible(new GitRef(playbooks, "v1.0.0", null, null), null, null, null, null, null))
                .contextWrite(c -> CurrentIdentity.with(c, ana))
                .block();
        assertThat(ProviderYaml.parse(result.yaml()).owner()).isEqualTo("team-db");
        assertThat(result.notes()).noneMatch(n -> n.startsWith("Set metadata.owner"));
    }

    // ---------------------------------------------------------------- terraform

    @Test
    void aTerraformModuleInGitBecomesAPlanAndAnApplyPinnedToItsCommit() {
        ImportTerraform command = new ImportTerraform(new GitRef(modules, "v2.3.0", "modules/lb", null), null, null, "team-net", "vault:terraform/platform");
        TerraformImport result = handler.importTerraform(command).block();
        assertThat(result.source()).isEqualTo("git::file://" + modules + "//modules/lb");
        assertThat(result.version()).isEqualTo("v2.3.0");
        assertThat(result.commit()).isEqualTo(tfCommit);
        assertThat(result.variables()).extracting(TerraformModule.Variable::name).containsExactly("name", "targets", "admin_password");
        assertThat(result.outputs()).extracting(TerraformModule.Output::name).containsExactly("url");
        ProviderDefinition def = ProviderYaml.parse(result.yaml());
        assertThat(def.name()).isEqualTo("lb");
        assertThat(def.title()).isEqualTo("Lb (Terraform)");
        assertThat(def.spec().module()).isEqualTo(json("{ source: 'git::file://" + modules + "//modules/lb', version: v2.3.0, commit: '" + tfCommit + "' }"));
        assertThat(def.actions()).extracting(ProviderDefinition.Action::id).containsExactly("plan", "apply");
        assertThat(def.action("apply").orElseThrow().vars()).isEqualTo(json("""
                type: object
                required: [name]
                properties:
                  name: { type: string, description: What to call the load balancer }
                  targets: { type: array, items: { type: string }, default: [] }
                """));
        assertThat(ProviderChecks.check(result.yaml()).errors()).isEmpty();
        assertThat(result.notes()).containsExactly(
                "The module is pinned to commit " + tfCommit.substring(0, 12) + " of v2.3.0: a later push changes nothing until a new version of the provider is published.",
                "admin_password holds a secret, so it is not an input: the runner gives Terraform its value from Vault; Apply fails until it does.");
    }

    @Test
    void aFolderWithoutTerraformFilesNamesTheModulesBelowIt() {
        StepVerifier.create(handler.importTerraform(new ImportTerraform(new GitRef(modules, "v2.3.0", null, null), null, null, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("No Terraform files in the root of " + modules + " at v2.3.0; give git.path one of the module folders: modules/lb, modules/net"))
                .verify();
    }

    @Test
    void aBranchIsPinnedToItsCommitWithANoteToImportATag() {
        TerraformImport result = handler.importTerraform(new ImportTerraform(new GitRef(modules, null, "modules/net", null), null, null, null, null)).block();
        assertThat(result.version()).isEqualTo("main");
        assertThat(ProviderYaml.parse(result.yaml()).name()).isEqualTo("net");
        assertThat(result.notes()).contains("main is not a version number: the module is pinned to commit " + tfCommit.substring(0, 12)
                + "; import a release tag so module.version says which code runs.");
    }

    @Test
    void aRegistryModuleIsReadWhereTheRegistrySaysItsCodeIsAtTheNewestRelease() {
        when(registry.versions("acme/lb/aws", null)).thenReturn(Mono.just(List.of("2.4.0-rc1", "2.3.0", "2.2.0")));
        when(registry.locate("acme/lb/aws", "2.3.0", null))
                .thenReturn(Mono.just(new Location("acme/lb/aws", "2.3.0", "git::file://" + modules + "//modules/lb?ref=v2.3.0")));
        TerraformImport result = handler.importTerraform(new ImportTerraform(null, new Registry("acme/lb/aws", null, null), null, "team-net", null)).block();
        assertThat(result.source()).isEqualTo("acme/lb/aws");
        assertThat(result.version()).isEqualTo("2.3.0");
        assertThat(result.commit()).isEqualTo(tfCommit);
        ProviderDefinition def = ProviderYaml.parse(result.yaml());
        assertThat(def.name()).isEqualTo("lb");
        assertThat(def.spec().module()).isEqualTo(json("{ source: acme/lb/aws, version: 2.3.0 }"));
        assertThat(def.action("plan").orElseThrow().vars().path("required")).isEqualTo(json("[name]"));
        assertThat(result.notes()).first().asString().startsWith("The variables were read from file://" + modules + " at v2.3.0");
    }

    @Test
    void aModuleOfAnotherRegistryHostKeepsTheHostInItsAddress() {
        when(registry.host()).thenReturn("tfe.example.com");
        when(registry.locate("acme/lb/aws", "2.3.0", "vault:terraform/registry"))
                .thenReturn(Mono.just(new Location("acme/lb/aws", "2.3.0", "git::file://" + modules + "//modules/lb?ref=v2.3.0")));
        TerraformImport result = handler.importTerraform(new ImportTerraform(null,
                new Registry("tfe.example.com/acme/lb/aws", "v2.3.0", "vault:terraform/registry"), null, null, null)).block();
        assertThat(result.source()).isEqualTo("tfe.example.com/acme/lb/aws");
        StepVerifier.create(handler.importTerraform(new ImportTerraform(null, new Registry("other.example.com/acme/lb/aws", "2.3.0", null), null, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Modules are looked up in tfe.example.com; other.example.com is another registry"))
                .verify();
    }

    @Test
    void anArchiveModuleTakesTheRegistrysDescriptionOrIsRefused() {
        when(registry.locate(eq("acme/vpc/aws"), eq("5.0.0"), isNull()))
                .thenReturn(Mono.just(new Location("acme/vpc/aws", "5.0.0", "https://archivist.example.com/v1/object/vpc.tar.gz")));
        when(registry.details(eq("acme/vpc/aws"), eq("5.0.0"), isNull())).thenReturn(Mono.just(json("""
                root:
                  inputs:
                    - { name: cidr, type: string, default: '"10.0.0.0/16"', required: false }
                    - { name: name, type: string, required: true }
                  outputs:
                    - { name: vpc_id }
                """)));
        TerraformImport result = handler.importTerraform(new ImportTerraform(null, new Registry("acme/vpc/aws", "5.0.0", null), null, null, null)).block();
        assertThat(result.commit()).isNull();
        assertThat(ProviderYaml.parse(result.yaml()).action("apply").orElseThrow().vars()).isEqualTo(json("""
                type: object
                required: [name]
                properties:
                  name: { type: string }
                  cidr: { type: string, default: 10.0.0.0/16 }
                """));
        when(registry.details(eq("acme/vpc/aws"), eq("5.0.0"), isNull())).thenReturn(Mono.empty());
        StepVerifier.create(handler.importTerraform(new ImportTerraform(null, new Registry("acme/vpc/aws", "5.0.0", null), null, null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("The registry serves acme/vpc/aws 5.0.0 as an archive and does not describe its variables; import the module from its Git repository instead"))
                .verify();
    }

    @Test
    void aVersionConstraintOrABadAddressIsRefusedBeforeAnythingIsRead() {
        StepVerifier.create(handler.importTerraform(new ImportTerraform(null, new Registry("acme/lb/aws", "~> 2.0", null), null, null, null)))
                .expectErrorMessage("registry.version is an exact version, like 2.3.0; ~> 2.0 is not one")
                .verify();
        StepVerifier.create(handler.importTerraform(new ImportTerraform(null, new Registry("acme/lb", "2.0.0", null), null, null, null)))
                .expectErrorMessage("registry.module is namespace/name/provider, like terraform-aws-modules/vpc/aws; acme/lb is not one")
                .verify();
        StepVerifier.create(handler.importTerraform(new ImportTerraform(null, null, null, null, null)))
                .expectErrorMessage("Give the module's git (repository, ref and folder) or its registry address (registry.module), one of them")
                .verify();
        StepVerifier.create(handler.importTerraform(new ImportTerraform(new GitRef(modules, null, null, null), new Registry("acme/lb/aws", null, null), null, null, null)))
                .expectError(IllegalArgumentException.class)
                .verify();
        verify(registry, never()).versions(anyString(), any());
    }
}

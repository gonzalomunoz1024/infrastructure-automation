package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.catalog.domain.McpTools;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.workflows.SampleFixtures;
import com.iaf.platform.workflows.domain.WorkflowImports.ImportedWorkflow;

class WorkflowImportsTest {

    static final List<ProviderDefinition> PROVIDERS = SampleFixtures.providers();
    static final List<String> TYPES = SampleFixtures.resourceTypes().stream().map(ResourceTypeDefinition::name).toList();
    static final List<String> EXISTING = List.of("vm-restart", "create-vm");

    static List<String> errors(String yaml) {
        return WorkflowChecks.check(yaml, SampleContentTest.context()).stream().filter(Problem::isError).map(Problem::code).toList();
    }

    @Test
    void idpImportsATileAsOneCallPlusRegister() {
        ImportedWorkflow i = WorkflowImports.build("idp", "block-storage-request", PROVIDERS, TYPES, EXISTING);
        WorkflowDefinition d = WorkflowYaml.parse(i.yaml());
        assertThat(d.name()).isEqualTo("block-storage-request");
        assertThat(d.noun()).isEqualTo("block-storage");
        assertThat(d.allSteps()).as("no registered type is named block-storage, so the register step is left to the author").extracting(Step::type).containsExactly(StepType.IDP);
        assertThat(d.steps().get(0).withText("tile")).isEqualTo("block-storage-request");
        assertThat(d.steps().get(0).withNode("input").path("sizeGb").asText()).isEqualTo("{{ $input.sizeGb }}");
        assertThat(d.inputSchema().path("required").get(0).asText()).isEqualTo("sizeGb");
        assertThat(i.notes()).anyMatch(n -> n.contains("register step"));
        assertThat(errors(i.yaml())).as("an imported draft is a valid file").isEmpty();
    }

    @Test
    void ansibleImportsALibraryPlaybookWithItsVarsAndCommit() {
        ImportedWorkflow i = WorkflowImports.build("ansible", "ops-playbooks/vm.restart", PROVIDERS, TYPES, EXISTING);
        WorkflowDefinition d = WorkflowYaml.parse(i.yaml());
        assertThat(d.name()).as("vm-restart exists, so the draft gets a name of its own").isEqualTo("vm-restart-from-ops-playbooks");
        assertThat(d.api().path()).as("and a route of its own, since vm-restart answers the natural one").isEqualTo("/api/v1/vms:restart-from-ops-playbooks");
        assertThat(d.verb()).isEqualTo("restart-from-ops-playbooks");
        assertThat(d.mcpTool()).as("and an MCP tool of its own, since vm-restart has vm_restart").isEqualTo("vm_restart_from_ops_playbooks");
        Step run = d.steps().get(0);
        assertThat(run.type()).isEqualTo(StepType.ANSIBLE);
        assertThat(run.withNode("playbook").path("git").path("repo").asText()).isEqualTo("github.com/acme/ops-playbooks");
        assertThat(run.withNode("playbook").path("git").path("path").asText()).isEqualTo("vm/restart.yml");
        assertThat(run.withNode("playbook").path("commit").asText()).startsWith("4e1d9a2c");
        assertThat(run.withNode("extraVars").path("vm_id").asText()).isEqualTo("{{ $input.vm_id }}");
        assertThat(run.withText("executionEnvironment")).isEqualTo("ee-platform:2.17");
        assertThat(run.retry().maxAttempts()).isEqualTo(1);
        assertThat(d.inputSchema().path("properties").has("target")).isTrue();
        assertThat(i.notes()).anyMatch(n -> n.contains("already exists"));
        assertThat(i.notes()).noneMatch(n -> n.contains("Pin the playbook"));
        assertThat(errors(i.yaml())).isEmpty();
    }

    @Test
    void ansibleImportsAGitUrlWithACommitNote() {
        ImportedWorkflow i = WorkflowImports.build("ansible", "github.com/acme/ops#v3.1.0:site/deploy.yml", PROVIDERS, TYPES, EXISTING);
        WorkflowDefinition d = WorkflowYaml.parse(i.yaml());
        assertThat(d.name()).isEqualTo("site-deploy");
        Step run = d.steps().get(0);
        assertThat(run.withNode("playbook").path("git").path("ref").asText()).isEqualTo("v3.1.0");
        assertThat(run.withNode("playbook").has("commit")).isFalse();
        assertThat(i.notes()).anyMatch(n -> n.contains("Pin the playbook to a commit"));
        assertThat(errors(i.yaml())).isEmpty();
        assertThatThrownBy(() -> WorkflowImports.build("ansible", "nonsense", PROVIDERS, TYPES, EXISTING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowImports.build("ansible", "ops-playbooks/nope", PROVIDERS, TYPES, EXISTING))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ops-playbooks/vm.restart");
    }

    @Test
    void terraformImportsPlanApprovalApply() {
        ImportedWorkflow i = WorkflowImports.build("terraform", "terraform-modules", PROVIDERS, TYPES, EXISTING);
        WorkflowDefinition d = WorkflowYaml.parse(i.yaml());
        assertThat(d.allSteps()).extracting(Step::type).containsExactly(StepType.TERRAFORM, StepType.APPROVAL, StepType.TERRAFORM);
        assertThat(d.steps().get(0).withText("action")).isEqualTo("plan");
        assertThat(d.steps().get(2).withText("action")).isEqualTo("apply");
        assertThat(d.steps().get(2).withNode("module").path("version").asText()).isEqualTo("2.3.0");
        assertThat(d.steps().get(2).withNode("vars").path("targets").asText()).isEqualTo("{{ $input.targets }}");
        assertThat(d.policy().approval().required()).isTrue();
        assertThat(errors(i.yaml())).isEmpty();
    }

    @Test
    void openapiImportsTheFirstCreateActionWithItsInputSchema() {
        ImportedWorkflow i = WorkflowImports.build("openapi", "vsphere", PROVIDERS, TYPES, EXISTING);
        WorkflowDefinition d = WorkflowYaml.parse(i.yaml());
        assertThat(d.name()).isEqualTo("create-vm-from-vsphere");
        assertThat(d.api().path()).isEqualTo("/api/v1/vms:create-from-vsphere");
        assertThat(d.mcpTool()).isEqualTo("vm_create_from_vsphere");
        assertThat(d.inputSchema().path("required").toString()).isEqualTo("[\"name\",\"templateId\"]");
        Step create = d.steps().get(0);
        assertThat(create.withText("provider")).isEqualTo("vsphere");
        assertThat(create.withText("action")).isEqualTo("vm.create");
        assertThat(create.withNode("input").path("templateId").asText()).isEqualTo("{{ $input.templateId }}");
        assertThat(d.steps().get(1).withText("type")).as("a registered type is named like the noun").isEqualTo("vm");
        assertThat(i.notes()).anyMatch(n -> n.contains("already exists"));
        assertThat(errors(i.yaml())).isEmpty();
        assertThatThrownBy(() -> WorkflowImports.build("openapi", "backup-nope", PROVIDERS, TYPES, EXISTING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowImports.build("nope", "x", PROVIDERS, TYPES, EXISTING)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDraftTakesAnMcpToolOfItsOwnWhenAPublishedWorkflowHasTheNaturalOne() {
        List<WorkflowDefinition> published = ContentFiles.ofKind(SampleFixtures.ROOT, "Workflow").stream().map(f -> WorkflowYaml.parse(f.yaml())).toList();
        List<String> tools = published.stream().flatMap(d -> McpTools.toolNames(d).stream()).toList();
        assertThat(tools).contains("vm_restart", "vm_restart_batch");

        // no workflow is named vm-restart here, but the published vm-restart has the tool vm_restart
        ImportedWorkflow i = WorkflowImports.build("ansible", "ops-playbooks/vm.restart", PROVIDERS, TYPES, List.of(), tools);
        WorkflowDefinition d = WorkflowYaml.parse(i.yaml());
        assertThat(d.name()).isEqualTo("vm-restart-from-ops-playbooks");
        assertThat(d.api().path()).isEqualTo("/api/v1/vms:restart-from-ops-playbooks");
        assertThat(d.mcpTool()).isEqualTo("vm_restart_from_ops_playbooks");
        assertThat(i.notes()).contains("A published workflow already has the MCP tool vm_restart, so this draft is named vm-restart-from-ops-playbooks,"
                + " answers /api/v1/vms:restart-from-ops-playbooks and has the MCP tool vm_restart_from_ops_playbooks;"
                + " change metadata.name, metadata.verb and api.path if you prefer others.");
        // the numbered name moves on while its tool is taken as well
        ImportedWorkflow second = WorkflowImports.build("ansible", "ops-playbooks/vm.restart", PROVIDERS, TYPES, List.of(),
                List.of("vm_restart", "vm_restart_from_ops_playbooks_batch"));
        assertThat(WorkflowYaml.parse(second.yaml()).mcpTool()).isEqualTo("vm_restart_from_ops_playbooks_2");
        // a tool nobody has keeps the natural name
        assertThat(WorkflowYaml.parse(WorkflowImports.build("ansible", "ops-playbooks/vm.restart", PROVIDERS, TYPES, List.of(), List.of("vm_create")).yaml())
                .name()).isEqualTo("vm-restart");

        // what the sample registry would refuse at publish, no import asks for
        for (String[] ref : new String[][] { { "idp", "block-storage-request" }, { "ansible", "ops-playbooks/vm.restart" }, { "terraform", "terraform-modules" },
                { "openapi", "vsphere" } }) {
            WorkflowDefinition draft = WorkflowYaml.parse(WorkflowImports.build(ref[0], ref[1], PROVIDERS, TYPES, published.stream().map(WorkflowDefinition::name).toList(),
                    tools).yaml());
            assertThat(PublishChecks.registryProblems(draft, published)).as(ref[0]).isEmpty();
        }
    }
}

package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.platform.workflows.SampleFixtures;
import com.iaf.platform.workflows.domain.ContractRules.Consumer;
import com.iaf.platform.workflows.domain.DependencyRules.Graph;

/** Breaking changes between two versions of a workflow and the consumers they reach. */
class ContractRulesTest {

    static final String CREATE_VM = SampleFixtures.yaml("workflows/team-compute/create-vm.yaml");

    static WorkflowDefinition createVm(String version, String yaml) {
        return WorkflowYaml.parse(yaml).withVersion(version);
    }

    static Graph samples() {
        return DependencyRules.of(DependencyRulesTest.samples(), DependencyRulesTest.sampleProducers());
    }

    @Test
    void addingThingsBreaksNobody() {
        WorkflowDefinition before = createVm("2.0.1", CREATE_VM);
        String added = CREATE_VM
                .replace("      owner:\n        type: string\n        title: Owner team\n",
                        "      owner:\n        type: string\n        title: Owner team\n      notes:\n        type: string\n")
                .replace("enum: [small, medium, large]", "enum: [small, medium, large, xlarge]");
        assertThat(added).contains("notes:").contains("xlarge");
        assertThat(ContractRules.breakingChanges(before, createVm("2.1.0", added))).isEmpty();
    }

    @Test
    void removedRetypedNarrowedAndNewlyRequiredInputsBreakConsumers() {
        WorkflowDefinition before = createVm("2.0.1", CREATE_VM);
        String changed = CREATE_VM
                .replace("      owner:\n        type: string\n        title: Owner team\n", "")
                .replace("enum: [small, medium, large]", "enum: [small, medium]")
                .replace("        title: Environment\n        enum: [dev, test, prod]\n        default: dev\n", "        title: Environment\n        enum: [dev, test, prod]\n")
                .replace("required: [name, image, size]", "required: [name, image, size, environment]")
                .replace("        type: string\n        title: VM name\n", "        type: integer\n        title: VM name\n")
                .replace("x-resource: { type: image, orCreateWith: build-vm-image }", "x-resource: { type: volume }");

        List<String> changes = ContractRules.breakingChanges(before, createVm("2.1.0", changed));

        assertThat(changes).containsExactly(
                "input 'name' changed type from string to integer",
                "input 'image' now points at type volume instead of image",
                "input 'size' no longer accepts \"large\"",
                "input 'owner' was removed",
                "input 'environment' is now required");
    }

    @Test
    void anIntegerFitsInANumberButNotTheOtherWayRound() {
        var f = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        assertThat(ContractRules.widens(f.textNode("integer"), f.textNode("number"))).isTrue();
        assertThat(ContractRules.widens(f.textNode("number"), f.textNode("integer"))).isFalse();
        assertThat(ContractRules.widens(f.textNode("string"), f.arrayNode().add("string").add("null"))).isTrue();
        assertThat(ContractRules.widens(null, f.textNode("string"))).as("no type accepted anything").isFalse();
        assertThat(ContractRules.widens(f.textNode("string"), null)).isTrue();
    }

    @Test
    void removedOutputsAndRegisteredTypesBreakConsumers() {
        WorkflowDefinition before = DependencyRulesTest.workflow("maker", "1.0.0", "name: { type: string }", """
                - save:
                    register: { type: vm, attributes: { name: x, os: linux } }
                - keep:
                    register: { type: image, attributes: { id: y } }
                """);
        WorkflowDefinition after = DependencyRulesTest.workflow("maker", "1.1.0", "name: { type: string }", """
                - save:
                    register: { type: vm, attributes: { name: x } }
                """);
        assertThat(ContractRules.breakingChanges(before, after)).containsExactly(
                "vm resources no longer get attribute 'os'",
                "it no longer registers image resources");

        WorkflowDefinition withOutputs = WorkflowYaml.parse(CREATE_VM);
        String fewer = CREATE_VM.replaceAll("(?m)^    vmId: .*\\n", "");
        assertThat(fewer).isNotEqualTo(CREATE_VM);
        assertThat(ContractRules.breakingChanges(withOutputs, WorkflowYaml.parse(fewer))).containsExactly("output 'vmId' was removed");
    }

    @Test
    void aNewMajorVersionIsAMajorBump() {
        assertThat(ContractRules.isMajorBump("2.0.1", "3.0.0")).isTrue();
        assertThat(ContractRules.isMajorBump("2.0.1", "2.9.0")).isFalse();
    }

    @Test
    void theConsumersAVersionReachesAreRunStepsWhoseRangeAcceptsItAndUsersOfItsTypes() {
        Graph g = samples();

        List<String> sameMajor = ContractRules.affected(g, "create-vm", "2.1.0", Set.of("vm")).stream().map(Consumer::describe).toList();
        assertThat(sameMajor).contains("web-environment (run ^2 at vm)");
        assertThat(sameMajor).anyMatch(d -> d.contains("use of vm") || d.contains("input of type vm"));
        assertThat(sameMajor).noneMatch(d -> d.startsWith("create-vm "));

        List<String> nextMajor = ContractRules.affected(g, "create-vm", "3.0.0", Set.of()).stream().map(Consumer::describe).toList();
        assertThat(nextMajor).doesNotContain("web-environment (run ^2 at vm)");
        assertThat(ContractRules.staying(g, "create-vm", "3.0.0")).extracting(Consumer::describe).containsExactly("web-environment (run ^2 at vm)");

        // orCreateWith reaches whatever version is current
        List<String> builders = new ArrayList<>(ContractRules.affected(g, "build-vm-image", "2.0.0", Set.of()).stream().map(Consumer::describe).toList());
        assertThat(builders).contains("create-vm (orCreateWith at image)", "web-environment (orCreateWith at image)");
    }
}

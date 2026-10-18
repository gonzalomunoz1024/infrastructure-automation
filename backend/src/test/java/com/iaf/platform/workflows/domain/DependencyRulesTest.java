package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.workflows.domain.DependencyRules.Graph;
import com.iaf.platform.workflows.domain.DependencyRules.RunLink;
import com.iaf.platform.workflows.domain.DependencyRules.TypeLink;
import com.iaf.platform.workflows.domain.DependencyRules.Via;

/** Producers, consumers and cycles, read from the files alone. */
class DependencyRulesTest {

    static List<WorkflowDefinition> samples() {
        return ContentFiles.ofKind(SampleContentTest.ROOT, "Workflow").stream().map(f -> WorkflowYaml.parse(f.yaml())).toList();
    }

    static Map<String, String> sampleProducers() {
        Map<String, String> producedBy = new HashMap<>();
        ContentFiles.ofKind(SampleContentTest.ROOT, ResourceTypeDefinition.KIND).stream()
                .map(f -> ResourceTypeDefinition.parse(f.yaml()))
                .forEach(t -> producedBy.put(t.name(), t.producedBy()));
        return producedBy;
    }

    static WorkflowDefinition workflow(String name, String version, String inputs, String steps) {
        return WorkflowYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: %s, title: %s, noun: thing, verb: make, owner: team-a, version: %s }
                api:
                  input:
                    type: object
                    properties:
                %s
                workflow:
                  do:
                %s
                """.formatted(name, name, version, inputs.indent(6).stripTrailing(), steps.indent(4).stripTrailing()));
    }

    @Test
    void producersComeFromProducedByAndRegisterSteps() {
        Graph g = DependencyRules.of(samples(), sampleProducers());

        assertThat(g.producersOf("image")).containsExactly("build-vm-image");
        assertThat(g.produces()).filteredOn(l -> l.type().equals("image")).extracting(TypeLink::via).contains(Via.PRODUCED_BY, Via.REGISTER);
        assertThat(g.typesProducedBy("web-environment")).containsExactly("environment");
        // delete-vm records the retirement of a vm with a register step
        assertThat(g.producersOf("vm")).contains("create-vm", "delete-vm");
    }

    @Test
    void consumersOfATypeAreResourceInputsAndUseSteps() {
        Graph g = DependencyRules.of(samples(), sampleProducers());

        assertThat(g.consumersOfType("image")).extracting(l -> l.workflow() + " " + l.via().json() + " " + l.at())
                .contains("create-vm input image", "create-vm use useImage", "web-environment input image");
        assertThat(g.typesConsumedBy("request-storage")).containsExactly("vm");
    }

    @Test
    void consumersOfAWorkflowAreItsRunStepsAndOrCreateWithInputs() {
        Graph g = DependencyRules.of(samples(), sampleProducers());

        assertThat(g.consumersOf("create-vm")).extracting(r -> r.consumer() + " " + r.range() + " " + r.at())
                .containsExactly("web-environment ^2 vm");
        assertThat(g.consumersOf("build-vm-image")).extracting(RunLink::consumer, RunLink::via, RunLink::at)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("create-vm", Via.OR_CREATE_WITH, "image"),
                        org.assertj.core.groups.Tuple.tuple("web-environment", Via.OR_CREATE_WITH, "image"));
        assertThat(g.runsOf("patch-cve")).extracting(RunLink::producer).containsExactly("vm-restart");
        assertThat(g.cycles()).isEmpty();
    }

    @Test
    void typesAreBuiltFromWhatTheirDeclaredProducerUsesAndRuns() {
        Map<String, java.util.Set<String>> deps = DependencyRules.of(samples(), sampleProducers()).typeDependencies();

        assertThat(deps.get("vm")).containsExactly("image");
        assertThat(deps.get("environment")).containsExactly("database", "vm");
        assertThat(deps.get("image")).isEmpty();
    }

    @Test
    void cyclesOfRunsAreReportedOnceFromTheirSmallestName() {
        WorkflowDefinition a = workflow("a", "1.0.0", "x: { type: string }", "- toB: { run: { workflow: b, version: \"^1\" } }");
        WorkflowDefinition b = workflow("b", "1.0.0", "x: { type: string }", "- toC: { run: { workflow: c } }");
        WorkflowDefinition c = workflow("c", "1.0.0", "thing: { type: string, x-resource: { type: thing, orCreateWith: a } }",
                "- note: { notify: { channel: '#x', message: hi } }");
        WorkflowDefinition self = workflow("self", "1.0.0", "x: { type: string }", "- again: { run: { workflow: self } }");
        WorkflowDefinition free = workflow("free", "1.0.0", "x: { type: string }", "- toA: { run: { workflow: a } }");

        Graph g = DependencyRules.of(List.of(free, self, c, b, a), Map.of());

        assertThat(g.cycles()).containsExactly(List.of("a", "b", "c", "a"), List.of("self", "self"));
        assertThat(g.cyclesThrough("b")).containsExactly(List.of("a", "b", "c", "a"));
        assertThat(g.cyclesThrough("free")).isEmpty();
        assertThat(g.runsOf("b")).singleElement().satisfies(r -> assertThat(r.range()).isNull());
    }

    @Test
    void aTypeNobodyDeclaredStillShowsItsLinks() {
        WorkflowDefinition maker = workflow("maker", "1.0.0", "x: { type: string }", "- keep: { register: { type: widget, attributes: { size: 1 } } }");
        WorkflowDefinition taker = workflow("taker", "1.0.0", "w: { type: string, x-resource: { type: widget } }",
                "- pick: { use: { type: widget, id: \"{{ $input.w }}\" } }");

        Graph g = DependencyRules.of(List.of(maker, taker), Map.of());

        assertThat(g.types()).containsExactly("widget");
        assertThat(g.producersOf("widget")).containsExactly("maker");
        assertThat(g.consumersOfType("widget")).extracting(TypeLink::via).containsExactly(Via.INPUT, Via.USE);
        assertThat(g.typeDependencies()).isEmpty();
    }

    @Test
    void aRunStepStartsTheHighestVersionInsideItsRange() {
        String note = "- note: { notify: { channel: '#x', message: hi } }";
        WorkflowDefinition older = workflow("maker", "1.2.3", "x: { type: string }", note);
        WorkflowDefinition one = workflow("maker", "1.4.0", "x: { type: string }", note);
        WorkflowDefinition two = workflow("maker", "2.0.1", "x: { type: string }", note);
        List<WorkflowDefinition> versions = List.of(older, two, one);

        assertThat(DependencyRules.resolve(versions, "^1")).containsSame(one);
        assertThat(DependencyRules.resolve(versions, "~1.2")).containsSame(older);
        assertThat(DependencyRules.resolve(versions, "1.2.3")).containsSame(older);
        assertThat(DependencyRules.resolve(versions, "^2")).containsSame(two);
        assertThat(DependencyRules.resolve(versions, "*")).containsSame(two);
        assertThat(DependencyRules.resolve(versions, "^3")).isEmpty();
        assertThat(DependencyRules.resolve(List.of(), "^1")).isEmpty();
        assertThat(DependencyRules.resolve(null, "^1")).isEmpty();
    }

    @Test
    void theDefaultRangeIsTheProducersCurrentMajor() {
        assertThat(DependencyRules.defaultRange("2.3.1")).isEqualTo("^2");
        assertThat(DependencyRules.defaultRange("0.9.0")).isEqualTo("^0");
        assertThat(DependencyRules.defaultRange(null)).isNull();
        assertThat(DependencyRules.defaultRange("two")).isNull();
    }

    @Test
    void aRunStepWithoutAVersionIsPinnedToTheProducersCurrentMajorAtPublish() {
        WorkflowDefinition consumer = workflow("consumer", "1.0.0", "x: { type: string }", """
                - make:
                    run: { workflow: create-vm, input: { name: web-1, image: img-1 } }
                - keep:
                    run: { workflow: create-database, version: "~1.4", input: { name: db } }
                - stray:
                    run: { workflow: nobody, input: {} }
                """);
        Map<String, String> current = Map.of("create-vm", "2.0.1", "create-database", "1.4.0");

        WorkflowDefinition pinned = DependencyRules.pinRanges(consumer, w -> java.util.Optional.ofNullable(current.get(w)));

        assertThat(pinned.step("make").orElseThrow().withText("version")).isEqualTo("^2");
        assertThat(pinned.step("keep").orElseThrow().withText("version")).as("a written range stays").isEqualTo("~1.4");
        assertThat(pinned.step("stray").orElseThrow().withText("version")).as("an unknown producer is left to the checks").isNull();
        assertThat(WorkflowYaml.toYaml(pinned)).contains("run:\n          workflow: create-vm\n          version: ^2\n          input:");
        assertThat(consumer.step("make").orElseThrow().withText("version")).as("the caller's definition is untouched").isNull();
        // nothing to pin: the same definition comes back
        assertThat(DependencyRules.pinRanges(pinned, w -> java.util.Optional.ofNullable(current.get(w)))).isSameAs(pinned);
    }

    @Test
    void resourceInputsNameTheirTypeAndTheWorkflowThatCanBuildOne() {
        WorkflowDefinition createVm = samples().stream().filter(w -> w.name().equals("create-vm")).findFirst().orElseThrow();

        assertThat(DependencyRules.resourceInputs(createVm)).containsExactly(new DependencyRules.ResourceInput("image", "image", "build-vm-image"));
        assertThat(DependencyRules.resourceInput(createVm, "image")).isPresent();
        assertThat(DependencyRules.resourceInput(createVm, "name")).isEmpty();
    }
}

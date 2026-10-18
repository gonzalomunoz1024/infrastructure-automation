package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.Problem;

/**
 * The conformance fixtures under packages/workflow-checks/fixtures: every rule the TypeScript
 * package implements, the Java checks implement the same way, judged on the same files.
 */
class WorkflowChecksTest {

    static final Path FIXTURES = SampleContentTest.ROOT.resolve("packages/workflow-checks/fixtures");

    @TestFactory
    Stream<DynamicTest> fixtures() throws IOException {
        Map<String, List<String>> expected = new ObjectMapper().readValue(FIXTURES.resolve("expected.json").toFile(), new TypeReference<>() {
        });
        CheckContext ctx = SampleContentTest.context();
        return expected.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
            String yaml = Files.readString(FIXTURES.resolve(e.getKey()));
            List<String> actual = new ArrayList<>(WorkflowChecks.check(yaml, ctx).stream().map(p -> p.level().json() + " " + p.code()).sorted().toList());
            assertThat(actual).as(e.getKey() + ": " + WorkflowChecks.check(yaml, ctx)).isEqualTo(e.getValue().stream().sorted().toList());
        }));
    }

    @Test
    void everyFixtureFileHasAnExpectation() throws IOException {
        Map<String, List<String>> expected = new ObjectMapper().readValue(FIXTURES.resolve("expected.json").toFile(), new TypeReference<>() {
        });
        try (Stream<Path> files = Files.list(FIXTURES)) {
            List<String> yamls = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yaml")).sorted().toList();
            assertThat(expected.keySet()).containsExactlyInAnyOrderElementsOf(yamls);
        }
    }

    @Test
    void didYouMeanFixesCarryTheReplacement() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: vm-info, title: VM info, noun: vm, verb: read, owner: team-compute, version: 1.0.0 }
                workflow:
                  do:
                    - getVm:
                        call: provider
                        with: { provider: vsphre, action: vm.get, input: { id: "{{ $input.vmId }}" } }
                """;
        List<Problem> problems = WorkflowChecks.check(yaml, SampleContentTest.context());
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).fix()).isNotNull();
        assertThat(problems.get(0).fix().value()).isEqualTo("vsphere");
        assertThat(problems.get(0).fix().label()).isEqualTo("Did you mean vsphere?");
    }

    @Test
    void aDeleteWithoutAnApprovalIsAWarningThatOffersAnInsertApprovalFix() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: vm-delete, title: Delete VM, noun: vm, verb: delete, owner: team-compute, version: 1.0.0 }
                workflow:
                  do:
                    - delete:
                        call: provider
                        with: { provider: vsphere, action: vm.delete, input: { id: "{{ $input.vmId }}" } }
                """;
        List<Problem> problems = WorkflowChecks.check(yaml, SampleContentTest.context());
        assertThat(problems).extracting(Problem::code).containsExactly("policy.approvalRequired");
        assertThat(problems.get(0).level()).isEqualTo(Problem.Level.WARNING);
        assertThat(problems.get(0).fix().kind()).isEqualTo("insert-approval");
        assertThat(WorkflowChecks.hasErrors(problems)).as("the provider's own checks keep the rule; the workflow may still publish").isFalse();
    }

    @Test
    void aFieldTheEarlierStepDoesNotReturnSuggestsTheDeclaredPath() throws IOException {
        List<Problem> problems = WorkflowChecks.check(Files.readString(FIXTURES.resolve("unknown-field.yaml")), SampleContentTest.context());

        assertThat(problems).extracting(Problem::code).containsOnly("expr.unknownField");
        assertThat(problems).extracting(Problem::message)
                .containsExactly("$vm has no field 'nmae'", "The previous step's output has no field 'attributesTemplateId'");
        assertThat(problems).extracting(p -> p.fix().value()).containsExactly("name", "attributes.templateId");
        assertThat(problems).extracting(p -> p.fix().label()).containsExactly("Did you mean name?", "Did you mean attributes.templateId?");
        assertThat(problems).allSatisfy(p -> {
            assertThat(p.step()).isEqualTo("tell");
            assertThat(p.field()).isEqualTo("message");
        });
    }

    @Test
    void aRunStepReadsOnlyTheOutputsTheWorkflowPublishes() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: vm-twice, title: VM twice, noun: vm, verb: twice, owner: team-compute, version: 1.0.0 }
                workflow:
                  do:
                    - make:
                        run: { workflow: create-vm, version: "^2", input: { name: web-1, image: img-1, size: small } }
                        output: { as: made }
                    - tell:
                        notify: { channel: "#ops", message: "{{ $made.outputs.vmid }} {{ $('make').json.outputs.name }} {{ $made.status }} {{ $made.error.message }} {{ $made.resources[0].id }}" }
                """;
        List<Problem> problems = WorkflowChecks.check(yaml, SampleContentTest.context());

        assertThat(problems).as("resources: what the child registered").singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("expr.unknownField");
            assertThat(p.message()).isEqualTo("$made has no field 'outputs.vmid'");
            assertThat(p.fix().value()).isEqualTo("outputs.vmId");
        });
    }

    @Test
    void fieldsAreNotJudgedWhenTheOutputIsNotDeclared() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: vm-quiet, title: VM quiet, noun: vm, verb: quiet, owner: team-compute, version: 1.0.0 }
                workflow:
                  do:
                    - restart:
                        call: provider
                        with: { provider: vsphere, action: vm.restart, input: { id: vm-1 } }
                    - tell:
                        notify: { channel: "#ops", message: "{{ $json.task }} {{ $json.anything.at.all }}" }
                """;
        assertThat(WorkflowChecks.check(yaml, SampleContentTest.context())).isEmpty();
        // with names only (no schemas) nothing is judged either
        CheckContext names = CheckContext.of(java.util.Map.of(), java.util.Set.of(), java.util.Set.of());
        assertThat(WorkflowChecks.check(yaml, names)).extracting(Problem::code).containsOnly("step.unknownProvider");
    }

    @Test
    void fieldsOfJsonAreJudgedOnlyWhereOneStepLeadsToIt() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: vm-branches, title: VM branches, noun: vm, verb: branch, owner: team-compute, version: 1.0.0 }
                workflow:
                  do:
                    - getVm:
                        call: provider
                        with: { provider: vsphere, action: vm.get, input: { id: vm-1 } }
                    - pick:
                        switch:
                          - case: { when: "{{ $json.name == 'x' }}", then: tell }
                    - read:
                        notify: { channel: "#ops", message: "{{ $json.nmae }}" }
                    - tell:
                        notify: { channel: "#ops", message: "{{ $json.nmae }}" }
                    - guarded:
                        try:
                          do:
                            - again:
                                call: provider
                                with: { provider: vsphere, action: vm.get, input: { id: vm-1 } }
                          catch:
                            do:
                              - note:
                                  notify: { channel: "#ops", message: "{{ $json.anything }}" }
                """;
        // read follows the switch, tell is a jump target, the catch starts from what the try started from
        assertThat(WorkflowChecks.check(yaml, SampleContentTest.context())).isEmpty();
    }

    @Test
    void aRunStepIsCheckedAgainstThePublishedInputOfItsWorkflow() throws IOException {
        List<Problem> problems = WorkflowChecks.check(Files.readString(FIXTURES.resolve("run-contract.yaml")), SampleContentTest.context());

        assertThat(problems).allSatisfy(p -> assertThat(p.step()).isEqualTo("first"));
        assertThat(problems).extracting(p -> p.code() + " " + p.field()).containsExactlyInAnyOrder(
                "contract.missingInput input.name",
                "contract.type input.size",
                "contract.type input.environment",
                "contract.unknownInput input.sise",
                "run.onFailure onFailure");
        Problem unknown = problems.stream().filter(p -> p.code().equals("contract.unknownInput")).findFirst().orElseThrow();
        assertThat(unknown.fix().value()).isEqualTo("size");
        assertThat(problems.stream().filter(p -> p.field().equals("input.environment")).findFirst().orElseThrow().message())
                .isEqualTo("'environment' is string in workflow create-vm, not integer");
        assertThat(problems.stream().filter(p -> p.field().equals("input.size")).findFirst().orElseThrow().message())
                .isEqualTo("'huge' is not one of [\"small\",\"medium\",\"large\"] in workflow create-vm");
    }

    @Test
    void stepsASwitchAlwaysJumpsPastNeverRun() throws IOException {
        List<Problem> problems = WorkflowChecks.check(Files.readString(FIXTURES.resolve("unreachable-step.yaml")), SampleContentTest.context());

        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("step.unreachable");
            assertThat(p.step()).isEqualTo("forgotten");
            assertThat(p.message()).isEqualTo("'forgotten' never runs: every case of pick jumps past it");
        });
        // without a default the switch can fall through, so the next step can run
        String fallsThrough = Files.readString(FIXTURES.resolve("unreachable-step.yaml")).replace("          - default: small\n", "");
        assertThat(WorkflowChecks.check(fallsThrough, SampleContentTest.context())).isEmpty();
    }

    @Test
    void withoutACatalogUnknownNamesAreNotReported() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: vm-info, title: VM info, noun: vm, verb: read, owner: team-compute, version: 1.0.0 }
                workflow:
                  do:
                    - getVm:
                        call: provider
                        with: { provider: whatever, action: anything, input: { id: "{{ $input.vmId }}" } }
                """;
        assertThat(WorkflowChecks.check(yaml, CheckContext.EMPTY)).isEmpty();
    }

    @Test
    void aKeyWrittenTwiceIsAYamlErrorNamingTheKey() throws IOException {
        List<Problem> problems = WorkflowChecks.check(Files.readString(FIXTURES.resolve("duplicate-key.yaml")), SampleContentTest.context());
        assertThat(problems).extracting(Problem::code).containsExactly("yaml.invalid");
        assertThat(problems.get(0).message()).contains("Duplicate field").contains("examples");
    }
}

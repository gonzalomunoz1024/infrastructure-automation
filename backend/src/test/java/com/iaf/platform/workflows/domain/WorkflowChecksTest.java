package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
    void aStoredDefinitionsApiInputIsMetaCheckedAndItsReferencesStayInside() throws IOException {
        // save and publish judge the stored definition: the api.input checks run there as in the designer
        WorkflowDefinition badInput = WorkflowYaml.parse(Files.readString(FIXTURES.resolve("bad-api-input.yaml")));
        assertThat(WorkflowChecks.check(badInput, SampleContentTest.context()))
                .extracting(Problem::code, Problem::field, Problem::message)
                .containsExactly(tuple("api.input.invalid", "api.input.required", "api.input is not a valid JSON Schema: required: string found, array expected"));
        WorkflowDefinition remote = WorkflowYaml.parse(Files.readString(FIXTURES.resolve("remote-ref.yaml")));
        assertThat(WorkflowChecks.check(remote, SampleContentTest.context()))
                .extracting(Problem::code, Problem::field)
                .containsExactly(tuple("schema.remoteRef", "api.input.properties.vmId.$ref"));
    }

    @Test
    void aStepThatNamesACredentialAnotherTeamOwnsIsRefusedNamingTheCredentialAndItsTeam() throws IOException {
        String vmRestart = Files.readString(SampleContentTest.ROOT.resolve("workflows/team-compute/vm-restart.yaml"));
        String withHeader = vmRestart.replace("          credential: vault:ssh/ops-automation\n",
                "          credential: vault:ssh/ops-automation\n          playbook2: { headers: { Authorization: \"Bearer vault:api/data-token\" } }\n");
        assertThat(withHeader).contains("Bearer vault:api/data-token");
        CheckContext ctx = SampleContentTest.context(Map.of("ssh/ops-automation", "team-data", "api/data-token", "team-data", "vsphere/token", "team-compute"));

        assertThat(WorkflowChecks.check(withHeader, ctx)).filteredOn(p -> p.code().equals("credential.owner"))
                .extracting(Problem::level, Problem::step, Problem::field, Problem::message)
                .containsExactly(
                        tuple(Problem.Level.ERROR, "restart", "credential", "Credential vault:ssh/ops-automation belongs to team-data; a workflow of team-compute may use its own team's credentials and unowned ones"),
                        tuple(Problem.Level.ERROR, "restart", "playbook2.headers.Authorization", "Credential vault:api/data-token belongs to team-data; a workflow of team-compute may use its own team's credentials and unowned ones"));
        // its own team's credential, an unowned one, a templated one and a vault that was not read are all fine
        assertThat(WorkflowChecks.check(vmRestart, SampleContentTest.context(Map.of("ssh/ops-automation", "team-compute")))).isEmpty();
        assertThat(WorkflowChecks.check(vmRestart, SampleContentTest.context(Map.of()))).isEmpty();
        assertThat(WorkflowChecks.check(vmRestart.replace("vault:ssh/ops-automation", "\"vault:ssh/{{ $input.reason }}\""), ctx))
                .extracting(Problem::code).doesNotContain("credential.owner");
        assertThat(WorkflowChecks.check(vmRestart, SampleContentTest.context())).isEmpty();
    }

    @Test
    void anMcpToolAnotherPublishedWorkflowHasIsAWarningNamingTheToolAndTheWorkflow() throws IOException {
        String yaml = Files.readString(FIXTURES.resolve("mcp-tool-name.yaml"));
        CheckContext ctx = SampleContentTest.context();
        String message = "MCP tool vm_restart is already exposed by workflow vm-restart; give this workflow another noun or verb, or set mcp.expose to false";
        assertThat(WorkflowChecks.check(yaml, ctx)).extracting(Problem::level, Problem::code, Problem::field, Problem::message)
                .containsExactly(tuple(Problem.Level.WARNING, "mcp.toolName", "mcp.expose", message));
        // the batch tool counts as well
        assertThat(WorkflowChecks.check(yaml.replace("verb: restart", "verb: restart-batch"), ctx)).extracting(Problem::message)
                .containsExactly(message.replace("tool vm_restart ", "tool vm_restart_batch "));
        // a new version of the same workflow, a workflow that is no tool and no registry at all are fine
        assertThat(WorkflowChecks.check(yaml.replace("name: vm-bounce", "name: vm-restart"), ctx)).isEmpty();
        assertThat(WorkflowChecks.check(yaml.replace("expose: true", "expose: false"), ctx)).isEmpty();
        assertThat(WorkflowChecks.check(yaml, CheckContext.EMPTY)).isEmpty();
        assertThat(ctx.mcpToolHolders("vm_restart")).containsExactly("vm-restart");
        assertThat(ctx.mcpToolHolders("vm_restart_batch")).containsExactly("vm-restart");
    }

    static WorkflowDefinition maker(String version, String sizeSchema, String output) {
        return WorkflowYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: maker, title: Maker, noun: thing, verb: make, owner: team-a, version: %s }
                api:
                  input:
                    type: object
                    properties:
                      size: %s
                workflow:
                  do:
                    - note: { notify: { channel: "#x", message: hi } }
                  outputs:
                    %s: "{{ $input.size }}"
                """.formatted(version, sizeSchema, output));
    }

    static String consumer(String range) {
        return """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: user, title: User, noun: thing, verb: use, owner: team-a, version: 1.0.0 }
                policy: { rbac: [ops] }
                workflow:
                  do:
                    - make:
                        name: Make
                        run: { workflow: maker, %sinput: { size: 4 } }
                    - tell:
                        notify: { channel: "#x", message: "{{ $('Make').json.outputs.old }}" }
                """.formatted(range == null ? "" : "version: \"" + range + "\", ");
    }

    @Test
    void aRunStepIsJudgedAgainstTheVersionItsRangeStartsNotTheCurrentOne() {
        // maker 2.0.1 is published and 1.4.0 retired: 1.x took an integer size and returned old, 2.x takes a word and returns fresh
        WorkflowDefinition one = maker("1.4.0", "{ type: integer }", "old");
        WorkflowDefinition two = maker("2.0.1", "{ type: string, enum: [small, large] }", "fresh");
        CheckContext ctx = CheckContext.of(Map.of(), List.of(two), List.of(one, two), Map.of(), null);
        Function<String, List<String>> judged = yaml -> WorkflowChecks.check(yaml, ctx).stream()
                .map(p -> p.code() + " @" + p.step() + "." + p.field() + ": " + p.message()).toList();

        assertThat(judged.apply(consumer("^1"))).as("1.x takes the integer and returns old").isEmpty();
        assertThat(judged.apply(consumer("^2"))).containsExactly(
                "contract.type @make.input.size: 'size' is string in workflow maker, not integer",
                "expr.unknownField @tell.message: The output of 'Make' has no field 'outputs.old'");
        // a step without a range is judged against the current version; a range no version satisfies, not at all (the run says so)
        assertThat(judged.apply(consumer(null))).extracting(s -> s.substring(0, s.indexOf(' '))).containsExactly("contract.type", "expr.unknownField");
        assertThat(judged.apply(consumer("^3"))).isEmpty();
        // the context that knows one version per workflow answers it for the ranges it satisfies
        CheckContext current = CheckContext.of(Map.of(), List.of(two), Map.of());
        assertThat(current.workflowInput("maker", "^2")).isEqualTo(current.workflowInput("maker"));
        assertThat(current.workflowInput("maker", "^1")).isEmpty();
        assertThat(ctx.workflowOutputs("maker", "^1")).contains(List.of("old"));
        assertThat(ctx.workflowOutputs("maker", null)).contains(List.of("fresh"));
    }

    @Test
    void didYouMeanFixesCarryTheReplacement() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: vm-info, title: VM info, noun: vm, verb: read, owner: team-compute, version: 1.0.0 }
                policy: { rbac: [ops] }
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
                policy: { rbac: [ops] }
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
                policy: { rbac: [ops] }
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
                policy: { rbac: [ops] }
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
                policy: { rbac: [ops] }
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
                policy: { rbac: [ops] }
                workflow:
                  do:
                    - getVm:
                        call: provider
                        with: { provider: whatever, action: anything, input: { id: "{{ $input.vmId }}" } }
                """;
        assertThat(WorkflowChecks.check(yaml, CheckContext.EMPTY)).isEmpty();
    }

    @Test
    void everyWorkflowNamesWhoMayRunIt() throws IOException {
        String yaml = Files.readString(FIXTURES.resolve("missing-policy.yaml"));
        assertThat(WorkflowChecks.check(yaml, SampleContentTest.context())).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("policy.missing");
            assertThat(p.field()).isEqualTo("policy");
        });
        String withoutRbac = yaml.replace("workflow:\n", "policy:\n  approval: { required: true, group: ops-approvers }\nworkflow:\n");
        assertThat(WorkflowChecks.check(withoutRbac, SampleContentTest.context())).extracting(Problem::field).containsExactly("policy.rbac");
        String emptyRbac = yaml.replace("workflow:\n", "policy:\n  rbac: []\nworkflow:\n");
        assertThat(WorkflowChecks.check(emptyRbac, SampleContentTest.context())).extracting(Problem::code).containsExactly("policy.missing");
        assertThat(WorkflowChecks.check(yaml.replace("workflow:\n", "policy: { rbac: [ops] }\nworkflow:\n"), SampleContentTest.context())).isEmpty();
    }

    @Test
    void anUploadDigestFromAnExpressionIsJudgedWhenTheStepRuns() throws IOException {
        String yaml = Files.readString(FIXTURES.resolve("bad-upload-digest.yaml")).replace("\"SHA256:9F2C0B7E\"", "\"{{ $input.sha256 }}\"");
        assertThat(WorkflowChecks.check(yaml, SampleContentTest.context())).extracting(Problem::code).doesNotContain("playbook.digest");
    }

    @Test
    void aKeyWrittenTwiceIsAYamlErrorNamingTheKey() throws IOException {
        List<Problem> problems = WorkflowChecks.check(Files.readString(FIXTURES.resolve("duplicate-key.yaml")), SampleContentTest.context());
        assertThat(problems).extracting(Problem::code).containsExactly("yaml.invalid");
        assertThat(problems.get(0).message()).contains("Duplicate field").contains("examples");
    }
}

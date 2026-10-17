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
    void deleteWithoutApprovalOffersAnInsertApprovalFix() {
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
        assertThat(problems.get(0).fix().kind()).isEqualTo("insert-approval");
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
}

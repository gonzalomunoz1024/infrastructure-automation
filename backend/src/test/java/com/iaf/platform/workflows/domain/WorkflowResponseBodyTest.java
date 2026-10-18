package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Problem;

/**
 * {@code api.response.body}: read as written, written back, and judged against the outputs the
 * workflow sets (the TypeScript checks hold the same cases, in response-body.test.ts).
 */
class WorkflowResponseBodyTest {

    static final String OUTPUTS = "  outputs: { powerState: \"{{ $('Get VM').json.powerState }}\" }";

    static String yaml(String response, String outputs) {
        return """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: vm-info, title: VM info, noun: vm, verb: read, owner: team-compute, version: 1.0.0 }
                policy: { rbac: [ops] }
                api:
                  path: /api/v1/vms/{vmId}:info
                  input: { type: object, required: [vmId], properties: { vmId: { type: string } } }
                %s
                workflow:
                  do:
                    - getVm:
                        name: Get VM
                        call: provider
                        with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.vmId }}" } }
                %s
                """.formatted(response == null ? "" : "  response: " + response, outputs == null ? "" : outputs);
    }

    static List<String> codes(String response, String outputs) {
        return WorkflowChecks.check(yaml(response, outputs), SampleContentTest.context()).stream().map(p -> p.level().name().toLowerCase() + " " + p.code()).sorted().toList();
    }

    @Test
    void theBodyIsReadAsWrittenAndSurvivesTheYamlRoundTrip() {
        WorkflowDefinition d = WorkflowYaml.parse(yaml("{ status: 202, body: { type: object, required: [powerState], properties: { powerState: { type: string, enum: [poweredOn, poweredOff] } } } }", OUTPUTS));
        assertThat(d.responseBody().path("required").get(0).asText()).isEqualTo("powerState");
        assertThat(d.responseBody().path("properties").path("powerState").path("enum")).hasSize(2);
        assertThat(d.api().responseStatus()).isEqualTo(202);

        String written = WorkflowYaml.toYaml(d);
        assertThat(written).contains("body:");
        WorkflowDefinition again = WorkflowYaml.parse(written);
        assertThat(again.responseBody()).isEqualTo(d.responseBody());
        assertThat(WorkflowYaml.toYaml(again)).isEqualTo(written);
        assertThat(WorkflowYaml.toJson(d).path("api").path("response").path("body")).isEqualTo(d.responseBody());
    }

    @Test
    void withoutABodyNothingIsReadOrWritten() {
        WorkflowDefinition d = WorkflowYaml.parse(yaml("{ status: 202 }", OUTPUTS));
        assertThat(d.responseBody()).isNull();
        assertThat(WorkflowYaml.toYaml(d)).doesNotContain("body:");
        assertThat(WorkflowYaml.parse(yaml(null, OUTPUTS)).responseBody()).isNull();
        assertThat(new WorkflowDefinition.Api("POST", "/api/v1/vms", null, 202, false, null).responseBody()).isNull();
        assertThat(new WorkflowDefinition.Api("POST", "/api/v1/vms", null, 202, false, null, null).responseBody()).isNull();
    }

    @Test
    void aBodyThatDescribesTheOutputsPasses() {
        assertThat(codes("{ status: 202, body: { type: object, properties: { powerState: { type: string } } } }", OUTPUTS)).isEmpty();
        assertThat(codes("{ status: 202, body: { properties: { powerState: { type: string } } } }", OUTPUTS)).isEmpty();
        assertThat(codes("{ status: 202, body: { type: object } }", null)).isEmpty();
        assertThat(codes("{ status: 202 }", null)).isEmpty();
    }

    @Test
    void theBodyIsAnObjectSchema() {
        assertThat(codes("{ status: 202, body: a JSON object }", OUTPUTS)).containsExactly("error api.responseBody");
        assertThat(codes("{ status: 202, body: { type: array, items: { type: string } } }", OUTPUTS)).containsExactly("error api.responseBody");
        assertThat(codes("{ status: 202, body: [powerState] }", OUTPUTS)).containsExactly("error api.responseBody");
        assertThat(codes("{ status: 202, body: null }", OUTPUTS)).containsExactly("error api.responseBody");
    }

    @Test
    void aPropertyTheOutputsNeverSetIsAWarning() {
        List<Problem> problems = WorkflowChecks.check(yaml("{ status: 202, body: { type: object, properties: { powerState: { type: string }, host: { type: string } } } }", OUTPUTS),
                SampleContentTest.context());
        assertThat(problems).extracting(Problem::code).containsExactly("api.responseOutput");
        assertThat(problems.get(0).field()).isEqualTo("api.response.body.host");
        assertThat(problems.get(0).message()).contains("'host'").contains("powerState");
        assertThat(codes("{ status: 202, body: { type: object, properties: { powerState: { type: string } } } }", null))
                .containsExactly("warning api.responseOutput");
    }
}

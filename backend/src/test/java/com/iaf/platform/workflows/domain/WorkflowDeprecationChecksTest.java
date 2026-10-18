package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Problem;

/** The {@code api.deprecated} block and {@code /api/v<n>/} paths, as the checks judge them. */
class WorkflowDeprecationChecksTest {

    static List<String> codes(String path, String deprecated) {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: vm-info, title: VM info, noun: vm, verb: read, owner: team-compute, version: 1.0.0 }
                policy: { rbac: [ops] }
                api:
                  path: %s
                  input: { type: object, required: [vmId], properties: { vmId: { type: string } } }
                %s
                workflow:
                  do:
                    - getVm:
                        call: provider
                        with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.vmId }}" } }
                """.formatted(path, deprecated == null ? "" : "  deprecated: " + deprecated);
        return WorkflowChecks.check(yaml, SampleContentTest.context()).stream().map(Problem::code).sorted().toList();
    }

    @Test
    void aCompleteBlockPasses() {
        assertThat(codes("/api/v1/vms/{vmId}:info", "{ since: 2026-11-01, sunset: 2027-05-01, replacement: \"/api/v2/vms/{vmId}:info\" }")).isEmpty();
        assertThat(codes("/api/v1/vms/{vmId}:info", "{ since: \"2026-11-01T09:00:00Z\", sunset: \"2027-05-01T00:00:00+02:00\", message: Use vm-info-v2 }")).isEmpty();
        assertThat(codes("/api/v1/vms/{vmId}:info", "{ since: 2026-11-01, sunset: 2027-05-01, replacement: \"https://docs.example.com/vm-info\" }")).isEmpty();
        assertThat(codes("/api/v1/vms/{vmId}:info", null)).isEmpty();
    }

    @Test
    void bothDatesAreRequiredAndTheSunsetComesAfter() {
        assertThat(codes("/api/v1/vms/{vmId}:info", "{ sunset: 2027-05-01 }")).containsExactly("deprecated.since");
        assertThat(codes("/api/v1/vms/{vmId}:info", "{ since: 2026-11-01 }")).containsExactly("deprecated.sunset");
        assertThat(codes("/api/v1/vms/{vmId}:info", "{ since: soon, sunset: 2027-02-30 }")).containsExactly("deprecated.since", "deprecated.sunset");
        assertThat(codes("/api/v1/vms/{vmId}:info", "{ since: 2027-05-01, sunset: 2027-05-01 }")).containsExactly("deprecated.sunset");
        assertThat(codes("/api/v1/vms/{vmId}:info", "true")).containsExactly("deprecated.since", "deprecated.sunset");
    }

    @Test
    void theReplacementIsAnotherPathOrAUrl() {
        assertThat(codes("/api/v1/vms/{vmId}:info", "{ since: 2026-11-01, sunset: 2027-05-01, replacement: vm-info-v2 }"))
                .containsExactly("deprecated.replacement");
        assertThat(codes("/api/v1/vms/{vmId}:info", "{ since: 2026-11-01, sunset: 2027-05-01, replacement: \"/api/v1/vms/{vmId}:info\" }"))
                .containsExactly("deprecated.replacement");
    }

    @Test
    void generatedPathsLiveUnderAnyApiVersionAndPlatformNounsStayReserved() {
        assertThat(codes("/api/v2/vms/{vmId}:info", null)).isEmpty();
        assertThat(codes("/api/v12/vms/{vmId}:info", null)).isEmpty();
        assertThat(codes("/api/vms/{vmId}:info", null)).containsExactly("api.path");
        assertThat(codes("/api/v0/vms/{vmId}:info", null)).containsExactly("api.path");
        assertThat(codes("/api/v2/jobs/{vmId}", null)).containsExactly("api.reservedPath");
        assertThat(codes("/api/v1/notifications/{vmId}", null)).containsExactly("api.reservedPath");
    }
}

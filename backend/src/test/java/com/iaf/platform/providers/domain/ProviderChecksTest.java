package com.iaf.platform.providers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.providers.domain.ProviderChecks.Result;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;

class ProviderChecksTest {

    static final String VALID = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: fleet, title: Fleet API, owner: team-compute, version: 1.0.0 }
            spec:
              type: openapi
              spec: { url: https://fleet.example.com/openapi.json, sha256: 9f2c1b7e0a4d5c6b8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d }
              endpoints: { dev: https://fleet-dev.example.com, prod: https://fleet.example.com }
              auth: { type: bearer, credential: vault:fleet/token }
              actions:
                - { id: vm.get, title: Get VM, operation: getVm, effect: read, retry: safe }
                - { id: vm.delete, title: Delete VM, operation: deleteVm, effect: delete, retry: unsafe, approval: required, plan: vm.get, verify: { action: vm.get, expect: notFound } }
              overrides:
                vm.get: { response: { properties: { powerState: { type: string } } } }
              errors:
                - { match: { status: 404 }, class: not_found, retry: false }
              slo: { successRate: 99.5, p95Ms: 800 }
              alerts: { channel: "#compute-alerts" }
            """;

    static List<String> codes(Result r) {
        return r.problems().stream().map(p -> p.level().json() + " " + p.code()).sorted().toList();
    }

    static Optional<Problem> problem(Result r, String code) {
        return r.problems().stream().filter(p -> p.code().equals(code)).findFirst();
    }

    static String status(Result r, String check) {
        return r.checks().stream().filter(c -> c.id().equals(check)).map(Check::status).findFirst().orElseThrow();
    }

    @Test
    void aValidProviderPassesEveryCheck() {
        Result r = ProviderChecks.check(VALID);
        assertThat(r.problems()).isEmpty();
        assertThat(r.checks()).extracting(Check::id).containsExactly("schema", "spec-pinned", "credentials", "actions", "slo");
        assertThat(r.checks()).extracting(Check::status).containsOnly("passed");
        assertThat(status(r, "spec-pinned")).isEqualTo("passed");
        assertThat(r.checks().get(1).detail()).contains("Pinned");
        assertThat(r.checks().get(3).detail()).isEqualTo("2 actions");
    }

    @Test
    void nameMustBeKebabCase() {
        Result r = ProviderChecks.check(VALID.replace("name: fleet", "name: Fleet_API"));
        assertThat(codes(r)).containsExactly("error name.format");
        assertThat(status(r, "schema")).isEqualTo("failed");
    }

    @Test
    void versionAndOwnerAreRequired() {
        Result r = ProviderChecks.check(VALID.replace("owner: team-compute, version: 1.0.0", "version: one"));
        assertThat(codes(r)).containsExactly("error metadata.owner", "error version.semver");
    }

    @Test
    void anUnknownTypeSuggestsTheClosestOne() {
        Result r = ProviderChecks.check(VALID.replace("type: openapi", "type: openapii"));
        assertThat(codes(r)).containsExactly("error type.unknown");
        assertThat(problem(r, "type.unknown").orElseThrow().fix().value()).isEqualTo("openapi");
        assertThat(status(r, "schema")).isEqualTo("failed");
        assertThat(status(r, "actions")).isEqualTo("skipped");
    }

    @Test
    void endpointsMustBeHttps() {
        Result r = ProviderChecks.check(VALID.replace("dev: https://fleet-dev.example.com", "dev: http://fleet-dev.example.com"));
        assertThat(codes(r)).containsExactly("error endpoints.https");
        Problem p = problem(r, "endpoints.https").orElseThrow();
        assertThat(p.field()).isEqualTo("spec.endpoints.dev");
        assertThat(p.fix().value()).isEqualTo("https://fleet-dev.example.com");
    }

    @Test
    void anHttpProviderNeedsEndpointsButAnAnsibleOneDoesNot() {
        String http = VALID.replace("type: openapi", "type: http")
                .replace("endpoints: { dev: https://fleet-dev.example.com, prod: https://fleet.example.com }", "")
                .replace("operation: getVm", "operation: { method: GET, path: \"/vms/{id}\" }")
                .replace("operation: deleteVm", "operation: { method: DELETE, path: \"/vms/{id}\" }");
        assertThat(codes(ProviderChecks.check(http))).contains("error endpoints.missing");
        assertThat(codes(ProviderChecks.check(ANSIBLE))).doesNotContain("error endpoints.missing");
    }

    @Test
    void aLiteralSecretIsAnErrorWithAVaultFix() {
        Result r = ProviderChecks.check(VALID.replace("credential: vault:fleet/token", "credential: abc123"));
        assertThat(codes(r)).containsExactly("error credential.plain");
        assertThat(problem(r, "credential.plain").orElseThrow().fix().value()).isEqualTo("vault:fleet/token");
        assertThat(status(r, "credentials")).isEqualTo("failed");
    }

    @Test
    void aMissingCredentialIsAnError() {
        Result r = ProviderChecks.check(VALID.replace("auth: { type: bearer, credential: vault:fleet/token }", "auth: { type: bearer }"));
        assertThat(codes(r)).containsExactly("error credential.missing");
    }

    @Test
    void headerAuthNamesItsHeader() {
        Result r = ProviderChecks.check(VALID.replace("auth: { type: bearer, credential: vault:fleet/token }", "auth: { type: header, credential: vault:fleet/api-key }"));
        assertThat(codes(r)).containsExactly("error auth.header");
    }

    @Test
    void secretsHiddenInActionsAreCaught() {
        Result r = ProviderChecks.check(VALID.replace("operation: getVm, effect: read", "operation: getVm, apiKey: hunter2, effect: read"));
        assertThat(codes(r)).containsExactly("error credential.plain");
        Problem p = problem(r, "credential.plain").orElseThrow();
        assertThat(p.step()).isEqualTo("vm.get");
        assertThat(p.field()).isEqualTo("spec.actions[0].apiKey");
    }

    @Test
    void actionIdsMustBeUnique() {
        Result r = ProviderChecks.check(VALID.replace("id: vm.delete", "id: vm.get"));
        assertThat(codes(r)).contains("error action.duplicateId");
        assertThat(problem(r, "action.duplicateId").orElseThrow().step()).isEqualTo("vm.get");
    }

    @Test
    void actionIdsReadAsNounDotVerb() {
        Result r = ProviderChecks.check(VALID.replace("id: vm.get", "id: getVm").replace("plan: vm.get", "plan: getVm").replace("action: vm.get", "action: getVm").replace("vm.get:", "getVm:"));
        assertThat(codes(r)).containsExactly("warning action.idShape");
        assertThat(status(r, "actions")).isEqualTo("passed");
        assertThat(r.checks().get(3).detail()).contains("1 warning");
    }

    @Test
    void aDeleteNeedsAnApproval() {
        Result r = ProviderChecks.check(VALID.replace("approval: required", "approval: none"));
        assertThat(codes(r)).containsExactly("error action.approvalRequired");
        Problem p = problem(r, "action.approvalRequired").orElseThrow();
        assertThat(p.step()).isEqualTo("vm.delete");
        assertThat(p.fix().value()).isEqualTo("required");
        assertThat(p.fix().kind()).isEqualTo("replace");
    }

    @Test
    void anUnsafeRetryOnAReadIsAWarning() {
        Result r = ProviderChecks.check(VALID.replace("effect: read, retry: safe", "effect: read, retry: unsafe"));
        assertThat(codes(r)).containsExactly("warning action.retryUnsafeRead");
    }

    @Test
    void overridesPlanAndVerifyMustNameExistingActions() {
        Result r = ProviderChecks.check(VALID.replace("vm.get: { response", "vm.gett: { response").replace("plan: vm.get", "plan: vm.gett").replace("verify: { action: vm.get", "verify: { action: vm.gett"));
        assertThat(codes(r)).containsExactly("error action.unknownPlan", "error action.unknownVerify", "error override.unknownAction");
        assertThat(problem(r, "override.unknownAction").orElseThrow().fix().value()).isEqualTo("vm.get");
    }

    @Test
    void sloRangesAreChecked() {
        Result r = ProviderChecks.check(VALID.replace("slo: { successRate: 99.5, p95Ms: 800 }", "slo: { successRate: 150, p95Ms: 0 }"));
        assertThat(codes(r)).containsExactly("error slo.p95Ms", "error slo.successRate");
        assertThat(status(r, "slo")).isEqualTo("failed");
    }

    @Test
    void aMissingAlertChannelIsAWarning() {
        Result r = ProviderChecks.check(VALID.replace("alerts: { channel: \"#compute-alerts\" }", ""));
        assertThat(codes(r)).containsExactly("warning alerts.channel");
    }

    @Test
    void anOpenapiSpecMustBePinned() {
        Result r = ProviderChecks.check(VALID.replace(", sha256: 9f2c1b7e0a4d5c6b8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d", ""));
        assertThat(codes(r)).containsExactly("error spec.unpinned");
        assertThat(status(r, "spec-pinned")).isEqualTo("failed");
        assertThat(codes(ProviderChecks.check(VALID.replace("sha256: 9f2c1b7e0a4d5c6b8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d", "sha256: abc"))))
                .containsExactly("error spec.sha256");
    }

    @Test
    void openapiActionsNameTheirOperation() {
        Result r = ProviderChecks.check(VALID.replace("operation: getVm, ", ""));
        assertThat(codes(r)).containsExactly("error action.operation");
    }

    static final String ANSIBLE = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: playbooks, title: Playbooks, owner: team-platform, version: 1.0.0 }
            spec:
              type: ansible
              source: { git: { repo: github.com/acme/ops, ref: v1 } }
              auth: { type: none }
              actions:
                - { id: vm.restart, title: Restart, playbook: vm/restart.yml, effect: change, retry: unsafe }
              slo: { successRate: 97, p95Ms: 240000 }
              alerts: { channel: "#platform-alerts" }
            """;

    @Test
    void ansibleWithoutACommitIsAWarning() {
        Result r = ProviderChecks.check(ANSIBLE);
        assertThat(codes(r)).containsExactly("warning ansible.unpinned");
        assertThat(status(r, "spec-pinned")).isEqualTo("skipped");
        assertThat(status(r, "credentials")).isEqualTo("skipped");
        assertThat(codes(ProviderChecks.check(ANSIBLE.replace("ref: v1 }", "ref: v1 }, commit: 4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d")))).isEmpty();
        assertThat(codes(ProviderChecks.check(ANSIBLE.replace("playbook: vm/restart.yml, ", "")))).contains("error action.playbook");
    }

    @Test
    void terraformNeedsAModuleVersionAndKeepsItsFixedActionIds() {
        String terraform = """
                apiVersion: iaf.platform/v1
                kind: Provider
                metadata: { name: tf-modules, title: Terraform, owner: team-platform, version: 2.0.0 }
                spec:
                  type: terraform
                  module: { source: git::https://github.com/acme/tf//lb }
                  auth: { type: none }
                  actions:
                    - { id: plan, effect: read, retry: safe }
                    - { id: apply, effect: create, retry: unsafe, plan: plan }
                  alerts: { channel: "#platform-alerts" }
                """;
        Result r = ProviderChecks.check(terraform);
        assertThat(codes(r)).containsExactly("error terraform.version");
        assertThat(codes(ProviderChecks.check(terraform.replace("//lb }", "//lb, version: 2.0.0 }")))).isEmpty();
    }

    @Test
    void invalidYamlIsOneProblemAndSkipsTheOtherChecks() {
        Result r = ProviderChecks.check("kind: [");
        assertThat(codes(r)).containsExactly("error yaml.invalid");
        assertThat(status(r, "schema")).isEqualTo("failed");
        assertThat(status(r, "slo")).isEqualTo("skipped");
        assertThat(codes(ProviderChecks.check("kind: Workflow\nmetadata: { name: x }\n"))).contains("error kind.invalid");
    }

    @Test
    void hasErrorsLooksAtTheLevel() {
        assertThat(ProviderChecks.check(VALID).hasErrors()).isFalse();
        assertThat(ProviderChecks.check(ANSIBLE).hasErrors()).isFalse();
        assertThat(ProviderChecks.check("kind: [").hasErrors()).isTrue();
    }
}

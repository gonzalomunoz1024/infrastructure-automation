package com.iaf.platform.providers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.providers.domain.ProviderReview.Result;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;

class ProviderReviewTest {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();
    static final String YAML = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: fleet, title: Fleet API, owner: team-compute, version: 1.0.0 }
            spec:
              type: openapi
              spec: { url: https://fleet.example.com/openapi.json, sha256: 9f2c1b7e0a4d5c6b8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d }
              endpoints: { dev: https://fleet-dev.example.com, test: https://fleet-test.example.com, prod: https://fleet.example.com }
              auth: { type: bearer, credential: vault:fleet/token }
              actions:
                - id: vm.get
                  operation: getVm
                  effect: read
                  output:
                    type: object
                    properties:
                      id: { type: string }
                      name: { type: string }
                - id: vm.delete
                  operation: deleteVm
                  effect: delete
                  approval: required
                  plan: vm.get
                  verify: { action: vm.get, expect: notFound }
              errors:
                - { match: { status: 404 }, class: not_found, retry: false }
              slo: { successRate: 99.5, p95Ms: 800 }
            """;

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static ConnectionTest connection(String env, String endpoint, boolean ok) {
        return new ConnectionTest(env, endpoint, "vault:fleet/token", ok, 15, ok ? "HEAD 200" : "HEAD 401", T0, "mateo");
    }

    static ActionTry answered(String action, String env, String response, long ms) {
        return new ActionTry(action, env, true, 200, ms, null, json(response), null, T0, "mateo");
    }

    static ActionTry failed(String action, String env, int status, String errorClass) {
        return new ActionTry(action, env, false, status, 40, errorClass, null, null, T0, "mateo");
    }

    /** dev and test answered, both actions answered in dev. */
    static List<Object> proof() {
        List<Object> all = new ArrayList<>();
        all.add(connection("dev", "https://fleet-dev.example.com", true));
        all.add(connection("test", "https://fleet-test.example.com/", true));
        all.add(answered("vm.get", "dev", "{\"id\":\"vm-1\",\"name\":\"web\"}", 200));
        all.add(answered("vm.delete", "dev", "{}", 400));
        return all;
    }

    static ReviewEvidence evidence(List<Object> items) {
        return new ReviewEvidence(items.stream().filter(ConnectionTest.class::isInstance).map(ConnectionTest.class::cast).toList(),
                items.stream().filter(ActionTry.class::isInstance).map(ActionTry.class::cast).toList());
    }

    static Result review(String yaml, List<Object> items) {
        return ProviderReview.review(ProviderYaml.parse(yaml), evidence(items));
    }

    static Check check(Result r, String id) {
        return r.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    static List<String> codes(Result r) {
        return r.problems().stream().map(Problem::code).toList();
    }

    @Test
    void aProviderProvedInTheWizardIsReady() {
        Result r = review(YAML, proof());
        assertThat(r.ready()).isTrue();
        assertThat(r.problems()).isEmpty();
        assertThat(r.checks()).extracting(Check::id).containsExactly("connection", "tried", "errors", "preview", "responses", "slo-suggested");
        assertThat(r.checks()).extracting(Check::status).containsOnly("passed");
        assertThat(check(r, "connection").detail()).isEqualTo("Answered: dev in 15 ms, test in 15 ms");
        assertThat(check(r, "tried").detail()).isEqualTo("2 of 2 actions answered in dev");
        assertThat(check(r, "responses").detail()).isEqualTo("2 of 2 responses match");
    }

    @Test
    void aTryCountsOnlyAtTheEndpointAndWithTheCredentialTheFileHasNow() {
        List<Object> items = new ArrayList<>(proof().subList(0, 2));
        items.add(new ActionTry("vm.get", "dev", true, 200, 200, null, json("{\"id\":\"vm-1\",\"name\":\"web\"}"), null, T0, "mateo",
                "https://fleet-dev.example.com/", "vault:fleet/token"));
        items.add(new ActionTry("vm.delete", "dev", true, 200, 400, null, json("{}"), null, T0, "mateo", "https://fleet-dev.example.com", "vault:fleet/token"));
        assertThat(review(YAML, items).ready()).isTrue();

        Result moved = review(YAML.replace("dev: https://fleet-dev.example.com", "dev: https://fleet-dev2.example.com"), items);
        assertThat(codes(moved)).containsExactly("connection.stale", "action.staleTry", "action.staleTry");
        assertThat(moved.problems().get(1).message())
                .isEqualTo("vm.get was tried at https://fleet-dev.example.com/, but the provider now points at https://fleet-dev2.example.com; try it again");
        assertThat(moved.problems().get(1).step()).isEqualTo("vm.get");

        Result rekeyed = review(YAML.replace("credential: vault:fleet/token", "credential: vault:fleet/token-2"), items);
        assertThat(codes(rekeyed)).containsExactly("connection.stale", "connection.stale", "action.staleTry", "action.staleTry");
        assertThat(rekeyed.problems().get(2).message())
                .isEqualTo("vm.get was tried with vault:fleet/token, but the provider now uses vault:fleet/token-2; try it again");
        // a try recorded before the endpoint was kept is taken as it is
        assertThat(codes(review(YAML.replace("dev: https://fleet-dev.example.com", "dev: https://fleet-dev2.example.com"), proof())))
                .containsExactly("connection.stale");
    }

    @Test
    void evidenceAStoreNoLongerKeepsIsCountedAndWarnedAbout() {
        ReviewEvidence e = evidence(proof());
        Result r = ProviderReview.review(ProviderYaml.parse(YAML), new ReviewEvidence(e.connections(), e.tries(), 3, 1));
        assertThat(r.ready()).isTrue();
        assertThat(codes(r)).containsExactly("evidence.dropped", "evidence.dropped");
        assertThat(r.problems().get(0).message()).startsWith("3 older connection tests of this provider are no longer kept");
        assertThat(r.problems().get(1).message()).startsWith("1 older try of this provider is no longer kept");
        assertThat(check(r, "tried").detail()).isEqualTo("2 of 2 actions answered in dev; 1 warning");
        assertThat(check(r, "connection").detail()).isEqualTo("Answered: dev in 15 ms, test in 15 ms; 1 warning");
    }

    // ---------------------------------------------------------------- connection

    @Test
    void nothingProvedMeansTheConnectionAndEveryActionAreMissing() {
        Result r = review(YAML, List.of());
        assertThat(r.ready()).isFalse();
        assertThat(codes(r)).containsExactly("connection.untested", "connection.untested", "action.untried", "action.untried");
        assertThat(r.problems().get(0).field()).isEqualTo("spec.endpoints.dev");
        assertThat(r.problems().get(1).message()).isEqualTo("Test the connection to test before publishing");
        assertThat(r.problems().get(2).step()).isEqualTo("vm.get");
        assertThat(check(r, "connection").status()).isEqualTo("failed");
        assertThat(check(r, "responses").status()).isEqualTo("skipped");
        assertThat(check(r, "slo-suggested").status()).isEqualTo("skipped");
        assertThat(r.slo()).isNull();
    }

    @Test
    void theLastConnectionTestCountsAndMustMatchTheFileAsItIsNow() {
        List<Object> items = proof();
        items.add(new ConnectionTest("test", "https://fleet-test.example.com", "vault:fleet/token", false, 30, "HEAD 401",
                T0.plusSeconds(60), "mateo"));
        Result failedLast = review(YAML, items);
        assertThat(codes(failedLast)).containsExactly("connection.failed");
        assertThat(failedLast.problems().get(0).message()).isEqualTo("The last connection test to test failed: HEAD 401");

        Result moved = review(YAML.replace("https://fleet-dev.example.com", "https://fleet-dev2.example.com"), proof());
        assertThat(codes(moved)).containsExactly("connection.stale");
        assertThat(moved.problems().get(0).message()).contains("dev was tested at https://fleet-dev.example.com, but the provider now points at "
                + "https://fleet-dev2.example.com");

        Result newCredential = review(YAML.replace("vault:fleet/token", "vault:fleet/other"), proof());
        assertThat(codes(newCredential)).containsExactly("connection.stale", "connection.stale");
        assertThat(newCredential.problems().get(0).field()).isEqualTo("spec.auth.credential");
    }

    @Test
    void withoutATestEndpointDevAloneIsRequiredAndTheGapIsAWarning() {
        String devAndProd = YAML.replace("test: https://fleet-test.example.com, ", "");
        Result r = review(devAndProd, proof());
        assertThat(r.ready()).isTrue();
        assertThat(codes(r)).containsExactly("connection.noTest");
        assertThat(check(r, "connection").status()).isEqualTo("passed");
        assertThat(check(r, "connection").detail()).isEqualTo("Answered: dev in 15 ms; 1 warning");
    }

    @Test
    void providersThatRunThroughARunnerSkipTheConnectionAndTheTries() {
        String ansible = """
                apiVersion: iaf.platform/v1
                kind: Provider
                metadata: { name: ops, title: Ops, owner: team-platform, version: 1.0.0 }
                spec:
                  type: ansible
                  source: { git: { repo: github.com/acme/ops, ref: main }, commit: 4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d }
                  actions:
                    - { id: vm.restart, playbook: vm/restart.yml, effect: change }
                """;
        Result r = review(ansible, List.of());
        assertThat(r.ready()).isTrue();
        assertThat(check(r, "connection").status()).isEqualTo("skipped");
        assertThat(check(r, "tried").status()).isEqualTo("skipped");
        assertThat(check(r, "errors").status()).isEqualTo("skipped");
        assertThat(check(r, "preview").status()).isEqualTo("skipped");
    }

    // ---------------------------------------------------------------- tried

    @Test
    void anActionThatOnlyFailedOrOnlyAnsweredOutsideDevIsNotTried() {
        List<Object> items = proof().subList(0, 2);
        items = new ArrayList<>(items);
        items.add(failed("vm.get", "dev", 404, "not_found"));
        items.add(answered("vm.delete", "test", "{}", 100));
        Result r = review(YAML, items);
        assertThat(codes(r)).containsExactly("action.tryFailed", "action.untried");
        assertThat(r.problems().get(0).message()).isEqualTo("vm.get failed every time it was tried against dev (last: HTTP 404, not_found); "
                + "try it with an input that exists");
    }

    @Test
    void aDeleteWhoseVerifyStillFindsTheThingIsAWarning() {
        List<Object> items = proof();
        items.add(new ActionTry("vm.delete", "dev", true, 200, 380, null, json("{}"), false, T0.plusSeconds(5), "mateo"));
        Result r = review(YAML, items);
        assertThat(r.ready()).isTrue();
        assertThat(codes(r)).containsExactly("action.notVerified");
        assertThat(r.problems().get(0).message()).startsWith("vm.delete answered, but vm.get still found the thing afterwards");
    }

    @Test
    void aProviderWithoutDevTriesItsFirstEnvironment() {
        String stagingOnly = YAML.replace("endpoints: { dev: https://fleet-dev.example.com, test: https://fleet-test.example.com, prod: https://fleet.example.com }",
                "endpoints: { staging: https://fleet-staging.example.com, prod: https://fleet.example.com }");
        ProviderDefinition def = ProviderYaml.parse(stagingOnly);
        assertThat(ProviderReview.sandbox(def)).isEqualTo("staging");
        assertThat(ProviderReview.connectionEnvironments(def)).containsExactly("staging");
        assertThat(ProviderReview.connectionEnvironments(ProviderYaml.parse(YAML))).containsExactly("dev", "test");
    }

    // ---------------------------------------------------------------- errors

    @Test
    void everyErrorClassNeedsAMatchRuleWithAStatus() {
        String yaml = YAML.replace("- { match: { status: 404 }, class: not_found, retry: false }",
                "- { match: { status: 404 }, class: not_found, retry: false }\n    - { class: quota }\n    - { match: { body: busy }, class: busy, retry: true }");
        Result r = review(yaml, proof());
        assertThat(codes(r)).containsExactly("errors.noMatch", "errors.noStatus");
        assertThat(r.problems().get(0).field()).isEqualTo("spec.errors[1].match");
        assertThat(check(r, "errors").status()).isEqualTo("failed");
    }

    @Test
    void anErrorAnsweredDuringTheTriesThatNoClassMatchesIsAWarning() {
        List<Object> items = proof();
        items.add(failed("vm.get", "dev", 422, "client_error"));
        items.add(failed("vm.get", "dev", 422, "client_error"));
        items.add(failed("vm.get", "dev", 404, "not_found"));
        Result r = review(YAML, items);
        assertThat(codes(r)).containsExactly("errors.unclassified");
        assertThat(r.problems().get(0).message()).startsWith("vm.get answered HTTP 422 when it was tried");
        Result none = review(YAML.replace("  errors:\n    - { match: { status: 404 }, class: not_found, retry: false }\n", ""), proof());
        assertThat(codes(none)).containsExactly("errors.none");
    }

    @Test
    void noErrorClassesBlocksAProviderThatChangesThingsAndWarnsAReadOnlyOne() {
        String noErrors = YAML.replace("  errors:\n    - { match: { status: 404 }, class: not_found, retry: false }\n", "");
        Result changes = review(noErrors, proof());
        assertThat(changes.errors()).extracting(Problem::code).as("vm.delete deletes things").containsExactly("errors.none");
        assertThat(changes.ready()).isFalse();
        assertThat(changes.checks()).filteredOn(c -> c.id().equals("errors")).extracting(Check::status).containsExactly("failed");

        String readOnly = noErrors.replace("""
                    - id: vm.delete
                      operation: deleteVm
                      effect: delete
                      approval: required
                      plan: vm.get
                      verify: { action: vm.get, expect: notFound }
                """, "");
        Result reads = review(readOnly, proof());
        assertThat(codes(reads)).containsExactly("errors.none");
        assertThat(reads.errors()).isEmpty();
        assertThat(reads.ready()).isTrue();
    }

    // ---------------------------------------------------------------- preview

    @Test
    void aDeleteNeedsAPlanActionOrADeclaredEffectDetail() {
        String noPlan = YAML.replace("      plan: vm.get\n", "");
        Result r = review(noPlan, proof());
        assertThat(codes(r)).containsExactly("action.noPreview");
        Problem p = r.problems().get(0);
        assertThat(p.step()).isEqualTo("vm.delete");
        assertThat(p.field()).isEqualTo("spec.actions[1].plan");
        assertThat(p.fix().label()).isEqualTo("Preview with vm.get");
        assertThat(p.fix().value()).isEqualTo("vm.get");

        Result declared = review(noPlan.replace("effect: delete", "effect: delete\n      effectDetail: Deletes the VM and its disks"), proof());
        assertThat(declared.ready()).isTrue();
        assertThat(check(declared, "preview").detail()).isEqualTo("1 destructive action previewed");
        Result unknownPlan = review(YAML.replace("plan: vm.get", "plan: vm.peek"), proof());
        assertThat(codes(unknownPlan)).contains("action.noPreview");
    }

    // ---------------------------------------------------------------- responses

    @Test
    void aRealResponseThatStillDisagreesWithTheSchemaIsFlaggedUntilAnOverrideFixesIt() {
        List<Object> items = proof();
        items.add(answered("vm.get", "dev", "{\"id\":\"vm-1\",\"name\":\"web\",\"powerState\":\"poweredOn\",\"cpu\":4}", 220));
        Result r = review(YAML, items);
        assertThat(codes(r)).containsExactly("action.responseMismatch");
        assertThat(r.problems().get(0).message()).isEqualTo("vm.get's real response still disagrees with its schema in 2 fields: "
                + "powerState (declared missing, got string), cpu (declared missing, got integer); use the real response to override the schema");
        assertThat(r.problems().get(0).field()).isEqualTo("spec.actions[0].output");

        String overridden = YAML.replace("  errors:", "  overrides:\n    vm.get:\n"
                + "      response: { properties: { powerState: { type: string }, cpu: { type: integer } } }\n  errors:");
        assertThat(review(overridden, items).ready()).isTrue();
    }

    // ---------------------------------------------------------------- slo

    @Test
    void theSloIsSuggestedFromTheTriedCallsAndNeverEnforced() {
        List<Object> items = proof();
        items.add(answered("vm.get", "dev", "{\"id\":\"vm-1\"}", 900));
        Result r = review(YAML, items);
        assertThat(r.slo().observedP95Ms()).isEqualTo(900);
        assertThat(r.slo().p95Ms()).isEqualTo(1350);
        assertThat(r.slo().calls()).isEqualTo(3);
        assertThat(r.slo().successRate()).isEqualTo(99.5);
        assertThat(r.ready()).as("a suggestion never blocks").isTrue();
        assertThat(codes(r)).containsExactly("slo.belowObserved");
        assertThat(r.problems().get(0).fix().value()).isEqualTo("1350");
        assertThat(check(r, "slo-suggested").status()).isEqualTo("passed");

        Result quick = review(YAML, List.of(answered("vm.get", "dev", "{}", 12)));
        assertThat(quick.slo().p95Ms()).as("never below 100 ms").isEqualTo(100);
    }
}

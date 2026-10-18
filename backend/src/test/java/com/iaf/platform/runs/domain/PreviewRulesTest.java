package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.JobStep.StepError;
import com.iaf.platform.runs.domain.Preview.Cost;
import com.iaf.platform.runs.domain.Preview.PreviewStep;
import com.iaf.platform.runs.domain.PreviewRules.CostPart;
import com.iaf.platform.runs.domain.PreviewRules.PlanAnswer;
import com.iaf.platform.runs.domain.PreviewRules.Recorded;
import com.iaf.platform.runs.domain.PreviewRules.ResourceRef;
import com.iaf.platform.runs.domain.PreviewRules.Walk;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/** A preview judged by a dry run: skipped branches, plans, built-first steps, cost and what a change touches. */
class PreviewRulesTest {

    static final Instant T = Instant.parse("2026-10-17T10:00:00Z");
    static final PreviewRules.Catalog CATALOG = PreviewRules.catalog(SampleContent.providers(), SampleContent::child);
    static final WorkflowDefinition CREATE_VM = SampleContent.workflow("create-vm");
    static final WorkflowDefinition DELETE_VM = SampleContent.workflow("delete-vm");
    static final WorkflowDefinition PATCH_CVE = SampleContent.workflow("patch-cve");
    static final WorkflowDefinition WEB_ENV = SampleContent.workflow("web-environment");
    static final List<PriceList> LISTS = List.of(PriceList.parse("""
            kind: PriceList
            metadata: { name: list-prices }
            spec:
              prices:
                - { provider: vsphere, action: vm.create, by: cpu, monthly: { 2: 38.5, 4: 77 } }
                - { provider: vsphere, action: vm.snapshot, monthly: 4.2 }
            """));

    static Step step(WorkflowDefinition def, String id) {
        return def.step(id).orElseThrow();
    }

    static Recorded ran(String key, Step s, JsonNode input, JsonNode output) {
        return new Recorded(key, StepEvent.succeeded(s, 1, input, output, T, 0, null));
    }

    static Recorded ran(String key, Step s) {
        return ran(key, s, JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode());
    }

    static Recorded failed(String key, Step s, JsonNode input) {
        return new Recorded(key, StepEvent.failed(s, 1, input, new StepError("not_found", "no such thing", false), T, 0));
    }

    static Recorded skipped(String key, Step s) {
        return new Recorded(key, StepEvent.skipped(s, T));
    }

    static JsonNode call(String provider, String action, Map<String, ?> input) {
        return SampleContent.json(Map.of("provider", provider, "action", action, "input", input));
    }

    static PreviewStep in(List<PreviewStep> steps, String id) {
        return steps.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    /** create-vm walked on a medium size: the large and small specs are the cases it does not take. */
    static Walk mediumVm(boolean passed, int cpu) {
        return PreviewRules.walkOf(passed, List.of(
                ran("useImage", step(CREATE_VM, "useImage"), SampleContent.json(Map.of("type", "image", "id", "img-77")), SampleContent.json(Map.of("id", "img-77"))),
                ran("sizeToSpec", step(CREATE_VM, "sizeToSpec")),
                ran("mediumSpec", step(CREATE_VM, "mediumSpec")),
                ran("createVm", step(CREATE_VM, "createVm"), call("vsphere", "vm.create", Map.of("name", "web-01", "cpu", cpu)),
                        SampleContent.json(Map.of("id", "vm-3", "monthlyCost", 38.5))),
                ran("waitReady", step(CREATE_VM, "waitReady")),
                ran("registerVm", step(CREATE_VM, "registerVm"))));
    }

    // ---------------------------------------------------------------- skipped steps

    @Test
    void aStepTheDryRunDoesNotTakeIsSkippedAndDoesNothing() {
        List<PreviewStep> steps = PreviewRules.refine(PreviewRules.steps(CREATE_VM, CATALOG), mediumVm(true, 4), Map.of(), CATALOG);

        assertThat(steps).extracting(PreviewStep::id)
                .containsExactly("useImage", "sizeToSpec", "largeSpec", "mediumSpec", "smallSpec", "createVm", "waitReady", "registerVm");
        assertThat(steps).filteredOn(PreviewStep::skipped).extracting(PreviewStep::id).containsExactly("largeSpec", "smallSpec");
        assertThat(in(steps, "largeSpec").effect()).isEqualTo(Effect.NONE);
        assertThat(in(steps, "largeSpec").detail()).isEqualTo("set · not taken in the dry run");
        assertThat(in(steps, "createVm").effect()).isEqualTo(Effect.CREATE);
        assertThat(in(steps, "createVm").skipped()).isFalse();
    }

    @Test
    void aDryRunThatFailsSkipsOnlyWhatItDecidedToSkip() {
        // patch-cve on a dev VM: the switch jumps over the approval, then the patch fails
        Walk walk = PreviewRules.walkOf(false, List.of(
                ran("useVm", step(PATCH_CVE, "useVm")),
                ran("needsApproval", step(PATCH_CVE, "needsApproval")),
                skipped("approval", step(PATCH_CVE, "approval")),
                failed("patch", step(PATCH_CVE, "patch"), SampleContent.json(Map.of("limit", "web-1")))));

        List<PreviewStep> steps = PreviewRules.refine(PreviewRules.steps(PATCH_CVE, CATALOG), walk, Map.of(), CATALOG);

        assertThat(steps).filteredOn(PreviewStep::skipped).extracting(PreviewStep::id).containsExactly("approval");
        assertThat(in(steps, "patch").effect()).isEqualTo(Effect.CHANGE);
        assertThat(in(steps, "reboot").effect()).as("never reached, so the static walk stands").isEqualTo(Effect.CHANGE);
        assertThat(in(steps, "verify").skipped()).isFalse();
    }

    @Test
    void aRunStepCarriesTheStrongestEffectOfItsChildStepsOnceTheyAreJudged() {
        List<Recorded> events = new ArrayList<>(List.of(
                ran("useVm", step(PATCH_CVE, "useVm")),
                ran("needsApproval", step(PATCH_CVE, "needsApproval")),
                skipped("approval", step(PATCH_CVE, "approval")),
                ran("patch", step(PATCH_CVE, "patch")),
                ran("rebootIfNeeded", step(PATCH_CVE, "rebootIfNeeded")),
                ran("reboot", step(PATCH_CVE, "reboot"))));
        WorkflowDefinition restart = SampleContent.workflow("vm-restart");
        for (String id : List.of("checkVmExists", "noBackupRunning", "approval", "validate", "notifyDone")) {
            events.add(ran("reboot/" + id, step(restart, id)));
        }
        events.add(skipped("reboot/restart", step(restart, "restart")));
        events.add(ran("verify", step(PATCH_CVE, "verify")));

        List<PreviewStep> statics = PreviewRules.steps(PATCH_CVE, CATALOG);
        List<PreviewStep> steps = PreviewRules.refine(statics, PreviewRules.walkOf(true, events), Map.of(), CATALOG);

        assertThat(in(statics, "reboot").effect()).as("statically the restart changes the VM").isEqualTo(Effect.CHANGE);
        assertThat(in(steps, "reboot/restart").skipped()).isTrue();
        assertThat(in(steps, "reboot").effect()).as("with the restart skipped the child only reads").isEqualTo(Effect.READ);
    }

    @Test
    void stepsOnlyTheDryRunKnowsJoinWhereItTookThem() {
        WorkflowDefinition build = SampleContent.workflow("build-vm-image");
        Step buildStep = Step.simple("image.create", "Build image", StepType.RUN, SampleContent.json(Map.of("workflow", "build-vm-image")));
        List<Recorded> events = new ArrayList<>(List.of(
                ran("image.create", buildStep),
                ran("image.create/startBuild", step(build, "startBuild"), call("image-builder", "build.start", Map.of("os", "rhel-9")), null),
                ran("image.create/registerReady", step(build, "registerReady"))));
        events.addAll(mediumVm(true, 4).steps().stream().map(w -> ran(w.key(), w.step(), w.input(), w.output())).toList());

        List<PreviewStep> steps = PreviewRules.refine(PreviewRules.steps(CREATE_VM, CATALOG), PreviewRules.walkOf(true, events), Map.of(), CATALOG);

        assertThat(steps).extracting(PreviewStep::id).startsWith("image.create", "image.create/startBuild", "image.create/registerReady", "useImage");
        assertThat(in(steps, "image.create").effect()).isEqualTo(Effect.CREATE);
        assertThat(in(steps, "image.create").detail()).isEqualTo("run build-vm-image");
        assertThat(in(steps, "image.create/startBuild").detail()).isEqualTo("image-builder build.start");
    }

    // ---------------------------------------------------------------- plans

    static final PreviewStep DELETE = new PreviewStep("delete", "Delete in vSphere", Effect.DELETE, "vsphere vm.delete (destructive)");

    @Test
    void aPlanThatFindsNothingLeavesAChangeOrADeleteNothingToDo() {
        PreviewStep p = PreviewRules.planned(DELETE, PlanAnswer.failed("vm.get", "not_found", "vsphere vm.get: vm-1 not found"));

        assertThat(p.effect()).isEqualTo(Effect.NONE);
        assertThat(p.detail()).isEqualTo("vsphere vm.delete (destructive) · plan vm.get: nothing to delete (vsphere vm.get: vm-1 not found)");
        PreviewStep create = new PreviewStep("createVm", "Create", Effect.CREATE, "vsphere vm.create");
        assertThat(PreviewRules.planned(create, PlanAnswer.failed("vm.get", "not_found", "gone")).detail()).isEqualTo("vsphere vm.create · plan vm.get: none there yet");
        assertThat(PreviewRules.planned(create, PlanAnswer.failed("vm.get", "not_found", "gone")).effect()).isEqualTo(Effect.CREATE);
    }

    @Test
    void aPlanNamesWhatAChangeOrADeleteActsOn() {
        PreviewStep p = PreviewRules.planned(DELETE, PlanAnswer.of("vm.get", SampleContent.json(Map.of("id", "vm-1", "name", "web-1", "powerState", "poweredOn"))));
        assertThat(p.effect()).isEqualTo(Effect.DELETE);
        assertThat(p.detail()).isEqualTo("vsphere vm.delete (destructive) · plan vm.get: web-1 (vm-1)");

        PreviewStep create = new PreviewStep("createVm", "Create", Effect.CREATE, "vsphere vm.create");
        assertThat(PreviewRules.planned(create, PlanAnswer.of("vm.get", SampleContent.json(Map.of("id", "vm-1", "name", "web-1")))))
                .as("a read answer adds nothing to a create").isEqualTo(create);
    }

    @Test
    void aPlanAnswerCanSayWhatTheStepWouldDoOrCountIt() {
        PreviewStep upgrade = new PreviewStep("upgrade", "Upgrade", Effect.CHANGE, "mongodb cluster.upgrade");
        PreviewStep same = PreviewRules.planned(upgrade, PlanAnswer.of("cluster.get", SampleContent.json(Map.of("effect", "none", "detail", "already at 7.0"))));
        assertThat(same.effect()).isEqualTo(Effect.NONE);
        assertThat(same.detail()).isEqualTo("mongodb cluster.upgrade · plan cluster.get: already at 7.0");

        PreviewStep counted = PreviewRules.planned(upgrade, PlanAnswer.of("diff", SampleContent.json(Map.of("add", 0, "change", 2, "destroy", 1))));
        assertThat(counted.effect()).isEqualTo(Effect.DELETE);
        assertThat(counted.detail()).isEqualTo("mongodb cluster.upgrade · plan diff: 0 to add, 2 to change, 1 to destroy");
    }

    @Test
    void aFailedPlanIsNotedAndTheEffectStays() {
        PreviewStep p = PreviewRules.planned(DELETE, PlanAnswer.failed("vm.get", "timeout", "no answer within 10s"));
        assertThat(p.effect()).isEqualTo(Effect.DELETE);
        assertThat(p.detail()).isEqualTo("vsphere vm.delete (destructive) · plan vm.get failed: no answer within 10s");
    }

    @Test
    void theReachedProviderStepsWhoseActionHasAPlanAreAskedWithTheirInput() {
        Walk walk = mediumVm(true, 4);

        assertThat(PreviewRules.planRequests(walk, CATALOG)).singleElement().satisfies(r -> {
            assertThat(r.key()).isEqualTo("createVm");
            assertThat(r.plan().id()).isEqualTo("vm.get");
            assertThat(r.input().path("cpu").asInt()).isEqualTo(4);
        });
        assertThat(PreviewRules.planRequests(Walk.NONE, CATALOG)).isEmpty();
    }

    @Test
    void aTerraformApplyIsJudgedByItsAnswerWhichIsItsPlan() {
        Step lb = step(WEB_ENV, "loadBalancer");
        List<PreviewStep> statics = List.of(new PreviewStep("loadBalancer", "Load balancer", Effect.CREATE, "terraform apply lb"));

        PreviewStep adds = PreviewRules.refine(statics, PreviewRules.walkOf(true, List.of(ran("loadBalancer", lb, null,
                SampleContent.json(Map.of("add", 3, "change", 0, "destroy", 0))))), Map.of(), CATALOG).get(0);
        assertThat(adds.effect()).isEqualTo(Effect.CREATE);
        assertThat(adds.detail()).isEqualTo("terraform apply lb · plan: 3 to add, 0 to change, 0 to destroy");

        PreviewStep nothing = PreviewRules.refine(statics, PreviewRules.walkOf(true, List.of(ran("loadBalancer", lb, null,
                SampleContent.json(Map.of("add", 0, "change", 0, "destroy", 0))))), Map.of(), CATALOG).get(0);
        assertThat(nothing.effect()).isEqualTo(Effect.NONE);
    }

    // ---------------------------------------------------------------- cost

    @Test
    void aCreateCallIsPricedByItsPlanThenThePriceListsThenItsSample() {
        WorkflowDefinition def = CREATE_VM;
        Walk walk = mediumVm(true, 4);
        List<PreviewStep> steps = PreviewRules.refine(PreviewRules.steps(def, CATALOG), walk, Map.of(), CATALOG);

        assertThat(PreviewRules.costParts(def, steps, walk, Map.of(), LISTS, CATALOG))
                .containsExactly(new CostPart("vsphere vm.create", 77, "USD", "price list list-prices"));
        assertThat(PreviewRules.costParts(def, steps, walk, Map.of(), List.of(), CATALOG))
                .containsExactly(new CostPart("vsphere vm.create", 38.5, "USD", "sample"));
        Map<String, PlanAnswer> plans = Map.of("createVm", PlanAnswer.of("vm.get", SampleContent.json(Map.of("monthlyCost", 81.25, "currency", "EUR"))));
        assertThat(PreviewRules.costParts(def, steps, walk, plans, LISTS, CATALOG))
                .containsExactly(new CostPart("vsphere vm.create", 81.25, "EUR", "plan vm.get"));
    }

    @Test
    void everyCallOfAStepInALoopIsPriced() {
        Step create = step(CREATE_VM, "createVm");
        Walk walk = PreviewRules.walkOf(true, List.of(
                ran("createVm", create, call("vsphere", "vm.create", Map.of("cpu", 2)), null),
                ran("createVm", create, call("vsphere", "vm.create", Map.of("cpu", 4)), null),
                ran("createVm", create, call("vsphere", "vm.create", Map.of("cpu", 2)), null)));
        List<PreviewStep> steps = List.of(new PreviewStep("createVm", "Create in vSphere", Effect.CREATE, "vsphere vm.create"));

        Cost cost = PreviewRules.total(PreviewRules.costParts(CREATE_VM, steps, walk, Map.of(), LISTS, CATALOG), 1);

        assertThat(cost.monthly()).isEqualTo(154.0);
        assertThat(cost.detail()).isEqualTo("vsphere vm.create 38.50 x 2 (price list list-prices) + vsphere vm.create 77.00 (price list list-prices)");
    }

    @Test
    void pastAFailedDryRunTheCreateStepsItNeverReachedArePricedFromTheFlow() {
        Walk walk = PreviewRules.walkOf(false, List.of(failed("useVm", step(DELETE_VM, "useVm"), SampleContent.json(Map.of("id", "vm-9")))));
        List<PreviewStep> steps = PreviewRules.refine(PreviewRules.steps(DELETE_VM, CATALOG), walk, Map.of(), CATALOG);

        assertThat(PreviewRules.costParts(DELETE_VM, steps, walk, Map.of(), LISTS, CATALOG))
                .containsExactly(new CostPart("vsphere vm.snapshot", 4.2, "USD", "price list list-prices"));
        assertThat(PreviewRules.costParts(DELETE_VM, steps, Walk.NONE, Map.of(), List.of(), CATALOG)).as("no price list and no sample price").isEmpty();
    }

    @Test
    void theTotalIsInTheFirstCurrencyAndCountsTheRowsOfABatch() {
        List<CostPart> parts = List.of(new CostPart("a create", 10, "USD", "sample"), new CostPart("b create", 2.5, "USD", "price list p"),
                new CostPart("c create", 7, "EUR", "plan c.get"));

        Cost cost = PreviewRules.total(parts, 3);

        assertThat(cost.monthly()).isEqualTo(37.5);
        assertThat(cost.currency()).isEqualTo("USD");
        assertThat(cost.detail()).isEqualTo("a create 10.00 (sample) + b create 2.50 (price list p) x 3 rows; not added: c create 7.00 (plan c.get) EUR");
        assertThat(PreviewRules.total(List.of(), 1)).isNull();
    }

    // ---------------------------------------------------------------- what the run touches

    @Test
    void theUseStepsTheDryRunReachedNameTheResourcesTheyRead() {
        assertThat(PreviewRules.usedRefs(mediumVm(true, 2))).containsExactly(new ResourceRef("image", "img-77", "step useImage"));
        assertThat(PreviewRules.usedRefs(Walk.NONE)).isEmpty();
        Walk built = PreviewRules.walkOf(true, List.of(ran("useImage", step(CREATE_VM, "useImage"), SampleContent.json(Map.of("type", "image", "id", "img-1")),
                SampleContent.json(Map.of("id", "img-1", "dryRun", true)))));
        assertThat(PreviewRules.usedRefs(built)).as("the dry run's own build is no resource yet").isEmpty();
    }

    @Test
    void whatAChangeOrADeleteNamesIsChangedAndEverythingIsWhenTheStepWasNotReached() {
        Step delete = step(DELETE_VM, "delete");
        List<PreviewStep> steps = List.of(new PreviewStep("useImage", "Use", Effect.READ, "use image"), DELETE);
        Walk took = PreviewRules.walkOf(true, List.of(ran("delete", delete, call("vsphere", "vm.delete", Map.of("id", "vm-1")), null)));

        assertThat(PreviewRules.changed(steps, took, Set.of("vm-1", "img-77"))).containsExactly("vm-1");
        assertThat(PreviewRules.changed(steps, Walk.NONE, Set.of("vm-1", "img-77"))).containsExactlyInAnyOrder("vm-1", "img-77");
        assertThat(PreviewRules.changed(List.of(steps.get(0)), took, Set.of("vm-1"))).as("nothing changes").isEmpty();
    }

    @Test
    void aStepIsMarkedSkippedOnTheWireOnlyWhenItIs() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode taken = mapper.valueToTree(new PreviewStep("a", "A", Effect.READ, "read"));
        ObjectNode skipped = mapper.valueToTree(new PreviewStep("b", "B", Effect.NONE, "set · not taken in the dry run", true));

        assertThat(taken.has("skipped")).isFalse();
        assertThat(skipped.path("skipped").asBoolean()).isTrue();
        assertThat(mapper.treeToValue(taken, PreviewStep.class)).isEqualTo(new PreviewStep("a", "A", Effect.READ, "read"));
        assertThat(mapper.treeToValue(skipped, PreviewStep.class).skipped()).isTrue();
    }
}

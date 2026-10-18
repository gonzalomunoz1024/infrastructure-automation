package com.iaf.platform.workflows.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.runs.ports.inbound.RunsQueries.JobOutcome;
import com.iaf.platform.workflows.SampleFixtures;
import com.iaf.platform.workflows.adapters.outbound.InMemoryChangeRequests;
import com.iaf.platform.workflows.adapters.outbound.InMemoryWorkflowStore;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.ReplayResult;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowCommands.DraftSaved;
import com.iaf.platform.workflows.ports.inbound.WorkflowCommands.ImportRequest;
import com.iaf.platform.workflows.ports.inbound.WorkflowCommands.PublishWorkflow;
import com.iaf.platform.workflows.ports.inbound.WorkflowCommands.WorkflowSource;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WorkflowCommandHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final JsonNodeFactory F = JsonNodeFactory.instance;
    static final Identity JUNIOR = new Identity("junior", "Junior", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity ANA = new Identity("ana", "Ana", null, List.of("team-data"), List.of("ops"), false, Identity.Kind.USER);

    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    InMemoryWorkflowStore store = InMemoryWorkflowStore.empty(clock);
    InMemoryChangeRequests changeRequests = new InMemoryChangeRequests();
    ProviderQueries providers = SampleFixtures.providerQueries();
    ResourceQueries resources = SampleFixtures.resourceQueries();
    CredentialsPort credentials = SampleFixtures.credentials();
    RunsCommands runs = SampleFixtures.runs();
    RunsQueries runsQueries = SampleFixtures.runsQueries();
    PolicyPort policy = SampleFixtures.policy();
    EventBus events = new EventBus(clock);
    List<DomainEvent> published = new ArrayList<>();
    WorkflowQueryHandler queries;
    WorkflowCommandHandler handler;

    String vmRestart = SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml");

    @BeforeEach
    void setUp() {
        store.seed(SampleFixtures.ROOT);
        events.register(published::add);
        queries = new WorkflowQueryHandler(store, changeRequests, providers, resources, credentials);
        handler = new WorkflowCommandHandler(store, changeRequests, queries, providers, resources, runs, runsQueries, policy, events, clock);
        handler.init();
    }

    List<DomainEvent> eventsOf(String type) {
        return published.stream().filter(e -> e.type().equals(type)).toList();
    }

    String edited(String yaml) {
        return yaml.replace("description: Restart a virtual machine after safety checks, with an approval for production.",
                "description: Restart a virtual machine after safety checks (edited).");
    }

    // ------------------------------------------------------------------ drafts

    @Test
    void savingADraftOfAPublishedWorkflowTakesTheNextPatch() {
        StepVerifier.create(handler.saveDraft("vm-restart", WorkflowSource.ofYaml(edited(vmRestart))))
                .assertNext(saved -> {
                    assertThat(saved.name()).isEqualTo("vm-restart");
                    assertThat(saved.version()).isEqualTo("1.2.1");
                    assertThat(saved.problems()).isEmpty();
                })
                .verifyComplete();
        WorkflowRecord draft = store.draft("vm-restart").orElseThrow();
        assertThat(draft.definition().version()).isEqualTo("1.2.1");
        assertThat(draft.path()).isEqualTo("workflows/team-compute/vm-restart.yaml");
        assertThat(store.published("vm-restart").orElseThrow().version()).isEqualTo("1.2.0");
        assertThat(eventsOf("workflow.draft-saved")).hasSize(1);
        assertThat(eventsOf("workflow.draft-saved").get(0).data().path("version").asText()).isEqualTo("1.2.1");
    }

    @Test
    void theAuthorMayChooseAHigherVersionAndSavingAgainUpdatesTheSameDraft() {
        String bumped = edited(vmRestart).replace("version: 1.2.0", "version: 2.0.0");
        DraftSaved first = handler.saveDraft("vm-restart", WorkflowSource.ofYaml(bumped)).block();
        assertThat(first.version()).isEqualTo("2.0.0");
        DraftSaved second = handler.saveDraft("vm-restart", WorkflowSource.ofYaml(bumped.replace("(edited)", "(edited twice)"))).block();
        assertThat(second.version()).isEqualTo("2.0.0");
        assertThat(store.versions("vm-restart")).extracting(WorkflowRecord::version).containsExactly("1.2.0", "2.0.0");
        assertThat(store.draft("vm-restart").orElseThrow().yaml()).contains("edited twice");
    }

    @Test
    void aNewWorkflowStartsAsDraftZeroOne() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: thing-refresh, title: Refresh a thing, noun: thing, verb: refresh, owner: team-compute }
                workflow:
                  do:
                    - say:
                        notify: { channel: "#x", message: "hi {{ $job.requester }}" }
                """;
        StepVerifier.create(handler.createDraft(WorkflowSource.ofYaml(yaml)))
                .assertNext(a -> {
                    assertThat(a.id()).isEqualTo("thing-refresh");
                    assertThat(a.status()).isEqualTo("draft");
                    assertThat(a.links()).containsKey("draft");
                })
                .verifyComplete();
        WorkflowRecord draft = store.draft("thing-refresh").orElseThrow();
        assertThat(draft.version()).isEqualTo("0.1.0");
        assertThat(draft.path()).isEqualTo("workflows/team-compute/thing-refresh.yaml");
        StepVerifier.create(handler.createDraft(WorkflowSource.ofYaml(yaml))).expectError(ConflictException.class).verify();
    }

    @Test
    void aDraftMayAlsoArriveAsTheJsonDocument() {
        JsonNode definition = WorkflowYaml.toJson(WorkflowYaml.parse(edited(vmRestart)));
        DraftSaved saved = handler.saveDraft("vm-restart", WorkflowSource.ofDefinition(definition)).block();
        assertThat(saved.version()).isEqualTo("1.2.1");
        assertThat(store.draft("vm-restart").orElseThrow().yaml()).contains("(edited)");
    }

    @Test
    void theDraftNameMustMatchThePath() {
        StepVerifier.create(handler.saveDraft("other-name", WorkflowSource.ofYaml(vmRestart))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.saveDraft("vm-restart", new WorkflowSource(null, null))).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void onlyTheOwnerTeamMayEdit() {
        String createDatabase = SampleFixtures.yaml("workflows/team-data/create-database.yaml");
        StepVerifier.create(handler.saveDraft("create-database", WorkflowSource.ofYaml(createDatabase)).contextWrite(ctx -> CurrentIdentity.with(ctx, JUNIOR)))
                .expectError(ForbiddenException.class)
                .verify();
        StepVerifier.create(handler.saveDraft("create-database", WorkflowSource.ofYaml(createDatabase)).contextWrite(ctx -> CurrentIdentity.with(ctx, ANA)))
                .assertNext(saved -> assertThat(saved.version()).isEqualTo("1.4.1"))
                .verifyComplete();
        assertThat(store.draft("create-database").orElseThrow().updatedBy()).isEqualTo("ana");
    }

    @Test
    void deleteRemovesDraftsOnly() {
        StepVerifier.create(handler.deleteDraft("vm-restart")).expectError(ConflictException.class).verify();
        handler.saveDraft("vm-restart", WorkflowSource.ofYaml(edited(vmRestart))).block();
        StepVerifier.create(handler.deleteDraft("vm-restart")).verifyComplete();
        assertThat(store.draft("vm-restart")).isEmpty();
        assertThat(store.published("vm-restart")).isPresent();
        assertThat(eventsOf("workflow.deleted")).hasSize(1);
    }

    @Test
    void validateReportsProblemsAgainstTheCatalog() {
        StepVerifier.create(handler.validate(WorkflowSource.ofYaml(vmRestart)))
                .assertNext(v -> assertThat(v.valid()).isTrue())
                .verifyComplete();
        StepVerifier.create(handler.validate(WorkflowSource.ofYaml(vmRestart.replace("provider: vsphere", "provider: vsphre"))))
                .assertNext(v -> {
                    assertThat(v.valid()).isFalse();
                    assertThat(v.problems()).extracting(Problem::code).contains("step.unknownProvider");
                })
                .verifyComplete();
        StepVerifier.create(handler.validate(WorkflowSource.ofYaml("kind: Workflow\nmetadata: [")))
                .assertNext(v -> assertThat(v.problems()).extracting(Problem::code).containsExactly("yaml.invalid"))
                .verifyComplete();
    }

    @Test
    void parseAndRenderRoundTripTheDocument() {
        var parsed = handler.parse(WorkflowSource.ofYaml(vmRestart)).block();
        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.definition().path("metadata").path("name").asText()).isEqualTo("vm-restart");
        assertThat(parsed.definition().path("workflow").path("do").get(0).has("checkVmExists")).isTrue();
        var rendered = handler.render(WorkflowSource.ofDefinition(parsed.definition())).block();
        assertThat(rendered.yaml()).isEqualTo(WorkflowYaml.toYaml(WorkflowYaml.parse(vmRestart)));
        var broken = handler.parse(WorkflowSource.ofYaml("metadata: [")).block();
        assertThat(broken.definition()).isNull();
        assertThat(broken.problems()).extracting(Problem::code).containsExactly("yaml.invalid");
    }

    @Test
    void dryRunDelegatesToTheRunsSlice() {
        StepVerifier.create(handler.dryRun(new com.iaf.platform.workflows.ports.inbound.WorkflowCommands.DryRunRequest(vmRestart, null, F.objectNode().put("vmId", "vm-1"))))
                .assertNext(r -> assertThat(r.passed()).isTrue())
                .verifyComplete();
    }

    // ------------------------------------------------------------------ publish -> merge

    @Test
    void publishOpensAChangeRequestWithEveryCheckAndMarksTheCandidate() {
        handler.saveDraft("vm-restart", WorkflowSource.ofYaml(edited(vmRestart))).block();
        CommandAccepted accepted = handler.publish("vm-restart", new PublishWorkflow("canary", "edited the description")).block();
        assertThat(accepted.id()).startsWith("cr_");
        assertThat(accepted.status()).isEqualTo("open");
        ChangeRequest cr = changeRequests.get(accepted.id()).orElseThrow();
        assertThat(cr.kind()).isEqualTo(ChangeRequest.Kind.WORKFLOW);
        assertThat(cr.name()).isEqualTo("vm-restart");
        assertThat(cr.version()).isEqualTo("1.2.1");
        assertThat(cr.branch()).isEqualTo("change/vm-restart-1.2.1");
        assertThat(cr.author()).isEqualTo("platform");
        assertThat(cr.strategy()).isEqualTo("canary");
        assertThat(cr.status()).isEqualTo(ChangeRequest.Status.OPEN);
        assertThat(cr.checks()).extracting(Check::id).containsExactly("schema", "checks", "ansible-lint", "policy", "replay", "contracts", "diff", "routes", "yaml");
        assertThat(cr.checks()).extracting(Check::status).containsOnly("passed");
        assertThat(cr.checksPassed()).isTrue();
        assertThat(cr.diff()).contains("--- a/workflows/team-compute/vm-restart.yaml").contains("+  description: Restart a virtual machine after safety checks (edited).");
        assertThat(cr.yaml()).contains("(edited)");
        assertThat(store.get("vm-restart", "1.2.1").orElseThrow().status()).isEqualTo(WorkflowStatus.CANDIDATE);
        assertThat(store.get("vm-restart", "1.2.1").orElseThrow().notes()).isEqualTo("edited the description");
        assertThat(store.draft("vm-restart")).isEmpty();
        DomainEvent opened = eventsOf("change-request.opened").get(0);
        assertThat(opened.aggregateId()).isEqualTo(cr.id());
        assertThat(opened.data().path("kind").asText()).isEqualTo("workflow");
        assertThat(opened.data().path("version").asText()).isEqualTo("1.2.1");
        // a second publish while the change request is open is refused
        handler.saveDraft("vm-restart", WorkflowSource.ofYaml(edited(vmRestart).replace("(edited)", "(again)"))).block();
        StepVerifier.create(handler.publish("vm-restart", null)).expectError(ConflictException.class).verify();
    }

    @Test
    void publishIsRefusedWhenTheChecksFindErrors() {
        handler.saveDraft("vm-restart", WorkflowSource.ofYaml(vmRestart.replace("provider: vsphere", "provider: vsphre"))).block();
        StepVerifier.create(handler.publish("vm-restart", new PublishWorkflow(null, null)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ConflictException.class).hasMessage("checks failed");
                    @SuppressWarnings("unchecked")
                    List<Problem> problems = (List<Problem>) ((ConflictException) e).detail();
                    assertThat(problems).extracting(Problem::code).contains("step.unknownProvider");
                })
                .verify();
        assertThat(changeRequests.all()).isEmpty();
        assertThat(store.draft("vm-restart")).as("the draft stays a draft").isPresent();
    }

    @Test
    void publishWithoutADraftOrWithABadStrategyIsRefused() {
        StepVerifier.create(handler.publish("vm-restart", null)).expectError(ConflictException.class).verify();
        StepVerifier.create(handler.publish("vm-restart", new PublishWorkflow("rolling", null))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.publish("nope", null)).expectError(com.iaf.platform.common.domain.NotFoundException.class).verify();
    }

    @Test
    void mergePublishesTheVersionRetiresThePreviousOneAndTellsEveryone() {
        handler.saveDraft("vm-restart", WorkflowSource.ofYaml(edited(vmRestart))).block();
        String crId = handler.publish("vm-restart", new PublishWorkflow("all", null)).block().id();
        StepVerifier.create(handler.mergeChangeRequest(crId))
                .assertNext(a -> {
                    assertThat(a.id()).isEqualTo(crId);
                    assertThat(a.status()).isEqualTo("merged");
                })
                .verifyComplete();
        assertThat(store.published("vm-restart").orElseThrow().version()).isEqualTo("1.2.1");
        assertThat(store.get("vm-restart", "1.2.0").orElseThrow().status()).isEqualTo(WorkflowStatus.RETIRED);
        WorkflowRecord published = store.get("vm-restart", "1.2.1").orElseThrow();
        assertThat(published.publishedAt()).isEqualTo(NOW);
        assertThat(published.publishedBy()).isEqualTo("platform");
        ChangeRequest cr = changeRequests.get(crId).orElseThrow();
        assertThat(cr.status()).isEqualTo(ChangeRequest.Status.MERGED);
        assertThat(cr.mergedAt()).isEqualTo(NOW);
        DomainEvent merged = eventsOf("change-request.merged").get(0);
        assertThat(merged.data().path("yaml").asText()).contains("(edited)");
        assertThat(merged.data().path("kind").asText()).isEqualTo("workflow");
        DomainEvent event = eventsOf("workflow.published").get(0);
        assertThat(event.aggregateId()).isEqualTo("vm-restart");
        assertThat(event.data().path("version").asText()).isEqualTo("1.2.1");
        assertThat(event.data().path("previous").asText()).isEqualTo("1.2.0");
        assertThat(event.data().path("strategy").asText()).isEqualTo("all");
        assertThat(event.data().path("by").asText()).isEqualTo("platform");
        StepVerifier.create(handler.mergeChangeRequest(crId)).as("merging twice").expectError(ConflictException.class).verify();
    }

    @Test
    void mergeIsRefusedWhileACheckFails() {
        when(policy.evaluate(any())).thenReturn(Mono.just(new PolicyDecision("deny", List.of(new PolicyPort.Rule("freeze", "deny", "change freeze")), "mock")));
        handler.saveDraft("vm-restart", WorkflowSource.ofYaml(edited(vmRestart))).block();
        String crId = handler.publish("vm-restart", null).block().id();
        ChangeRequest cr = changeRequests.get(crId).orElseThrow();
        assertThat(cr.checks()).filteredOn(c -> c.id().equals("policy")).extracting(Check::status).containsExactly("failed");
        assertThat(cr.strategy()).as("the file's rollout strategy when the body names none").isEqualTo("canary");
        StepVerifier.create(handler.mergeChangeRequest(crId))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ConflictException.class).hasMessage("checks failed");
                    assertThat(((ConflictException) e).detail().toString()).contains("policy");
                })
                .verify();
        assertThat(store.published("vm-restart").orElseThrow().version()).isEqualTo("1.2.0");
    }

    // ------------------------------------------------------------------ contracts between workflows

    String createVm = SampleFixtures.yaml("workflows/team-compute/create-vm.yaml");
    String withoutOwner = createVm.replace("      owner:\n        type: string\n        title: Owner team\n", "");

    @Test
    void publishPinsARunStepWithoutAVersionToTheProducersCurrentMajor() {
        String webEnvironment = SampleFixtures.yaml("workflows/team-platform/web-environment.yaml");
        String unpinned = webEnvironment.replace("workflow: create-vm\n                            version: \"^2\"\n", "workflow: create-vm\n");
        assertThat(unpinned).isNotEqualTo(webEnvironment);
        DraftSaved saved = handler.saveDraft("web-environment", WorkflowSource.ofYaml(unpinned)).block();
        assertThat(store.get("web-environment", saved.version()).orElseThrow().definition().step("vm").orElseThrow().withText("version")).isNull();

        String crId = handler.publish("web-environment", null).block().id();

        WorkflowRecord candidate = store.get("web-environment", saved.version()).orElseThrow();
        assertThat(candidate.status()).isEqualTo(WorkflowStatus.CANDIDATE);
        assertThat(candidate.definition().step("vm").orElseThrow().withText("version")).as("create-vm is 2.0.1").isEqualTo("^2");
        assertThat(WorkflowYaml.parse(changeRequests.get(crId).orElseThrow().yaml()).step("vm").orElseThrow().withText("version")).isEqualTo("^2");
        assertThat(candidate.contentHash()).isEqualTo(WorkflowYaml.contentHash(WorkflowYaml.parse(candidate.yaml())));
    }

    @Test
    void aBreakingChangeWithoutANewMajorVersionFailsTheContractsCheck() {
        handler.saveDraft("create-vm", WorkflowSource.ofYaml(withoutOwner)).block();
        String crId = handler.publish("create-vm", null).block().id();

        ChangeRequest cr = changeRequests.get(crId).orElseThrow();
        Check contracts = cr.checks().stream().filter(c -> c.id().equals("contracts")).findFirst().orElseThrow();
        assertThat(contracts.status()).isEqualTo("failed");
        assertThat(contracts.detail()).contains("input 'owner' was removed").contains("web-environment (run ^2 at vm)");
        assertThat(cr.checksPassed()).isFalse();
        StepVerifier.create(handler.mergeChangeRequest(crId)).expectError(ConflictException.class).verify();
    }

    @Test
    void aBreakingChangeWithANewMajorVersionPassesTheContractsCheck() {
        handler.saveDraft("create-vm", WorkflowSource.ofYaml(withoutOwner.replace("version: 2.0.1", "version: 3.0.0"))).block();
        String crId = handler.publish("create-vm", null).block().id();

        Check contracts = changeRequests.get(crId).orElseThrow().checks().stream().filter(c -> c.id().equals("contracts")).findFirst().orElseThrow();
        assertThat(contracts.status()).isEqualTo("passed");
        assertThat(contracts.detail()).startsWith("new major version 3.0.0").endsWith("Staying on the old major: web-environment (run ^2 at vm)");
    }

    @Test
    void validateAlsoReportsTheChangeRequestChecksTheFileWouldFail() {
        StepVerifier.create(handler.validate(WorkflowSource.ofYaml(withoutOwner)))
                .assertNext(v -> {
                    assertThat(v.valid()).isFalse();
                    Problem p = v.problems().stream().filter(x -> x.code().equals("publish.contracts")).findFirst().orElseThrow();
                    assertThat(p.message()).contains("2.0.2").contains("input 'owner' was removed");
                    assertThat(p.fix().value()).isEqualTo("3.0.0");
                })
                .verifyComplete();
        StepVerifier.create(handler.validate(WorkflowSource.ofYaml(withoutOwner.replace("version: 2.0.1", "version: 3.0.0"))))
                .assertNext(v -> assertThat(v.problems()).extracting(Problem::code).doesNotContain("publish.contracts"))
                .verifyComplete();
        String unpinned = vmRestart.replaceAll("\\s+commit: 4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d", "");
        StepVerifier.create(handler.validate(WorkflowSource.ofYaml(unpinned)))
                .assertNext(v -> {
                    assertThat(v.valid()).isFalse();
                    assertThat(v.problems()).extracting(Problem::code).contains("publish.ansibleLint").doesNotContain("ansible.unpinned");
                })
                .verifyComplete();
    }

    @Test
    void closeReturnsTheVersionToDraft() {
        handler.saveDraft("vm-restart", WorkflowSource.ofYaml(edited(vmRestart))).block();
        String crId = handler.publish("vm-restart", null).block().id();
        StepVerifier.create(handler.closeChangeRequest(crId)).assertNext(a -> assertThat(a.status()).isEqualTo("closed")).verifyComplete();
        assertThat(changeRequests.get(crId).orElseThrow().status()).isEqualTo(ChangeRequest.Status.CLOSED);
        assertThat(store.draft("vm-restart").orElseThrow().version()).isEqualTo("1.2.1");
        assertThat(eventsOf("change-request.closed")).hasSize(1);
        StepVerifier.create(handler.mergeChangeRequest(crId)).expectError(ConflictException.class).verify();
    }

    @Test
    void aProviderChangeRequestIsMergedByEventOnly() {
        String providerYaml = SampleFixtures.yaml("providers/team-compute/vsphere.yaml");
        ChangeRequest cr = new ChangeRequest("cr_prov", ChangeRequest.Kind.PROVIDER, "vsphere", "1.4.0", "change/vsphere-1.4.0", "Update vsphere", "mateo",
                ChangeRequest.Status.OPEN, List.of(new Check("schema", "Schema", "passed", null)), "all", providerYaml, "", NOW, null, null, null);
        changeRequests.save(cr);
        StepVerifier.create(handler.mergeChangeRequest("cr_prov")).assertNext(a -> assertThat(a.links()).containsKey("provider")).verifyComplete();
        assertThat(eventsOf("change-request.merged").get(0).data().path("kind").asText()).isEqualTo("provider");
        assertThat(eventsOf("change-request.merged").get(0).data().path("yaml").asText()).isEqualTo(providerYaml);
        assertThat(eventsOf("workflow.published")).isEmpty();
    }

    // ------------------------------------------------------------------ replay

    @Test
    void theReplayCheckDryRunsTheInputsOfRecentJobs() {
        DryRunResult failed = new DryRunResult(false, List.of("checkVmExists", "restart"),
                List.of(new DryRunResult.DryRunStep("checkVmExists", "Check VM exists", "succeeded", null, null, null),
                        new DryRunResult.DryRunStep("restart", "Restart with Ansible", "failed", null, null, "playbook not found")), null);
        when(runs.dryRun(any(), any(), any())).thenAnswer(inv -> {
            JsonNode input = inv.getArgument(1);
            return Mono.just("vm-2".equals(input.path("vmId").asText()) ? failed : SampleFixtures.DRY_RUN_PASSED);
        });
        when(runsQueries.outcomes(any(), any(), any())).thenReturn(Mono.just(List.of(
                new JobOutcome("job_1", "vm-restart", "1.2.0", "succeeded", NOW.minusSeconds(3600), NOW.minusSeconds(3000), 600_000, null, null, F.objectNode().put("vmId", "vm-1")),
                new JobOutcome("job_2", "vm-restart", "1.2.0", "succeeded", NOW.minusSeconds(1800), NOW.minusSeconds(1200), 600_000, null, null, F.objectNode().put("vmId", "vm-2")))));
        handler.saveDraft("vm-restart", WorkflowSource.ofYaml(edited(vmRestart))).block();

        StepVerifier.create(handler.replayCheck("vm-restart", "1.2.1"))
                .assertNext(r -> {
                    assertThat(r.passed()).isFalse();
                    assertThat(r.replayed()).isEqualTo(2);
                    assertThat(r.divergences()).containsExactly(new ReplayResult.Divergence("job_2", "restart", "playbook not found"));
                })
                .verifyComplete();
        // the publish carries the same verdict in its replay check
        String crId = handler.publish("vm-restart", null).block().id();
        Check replay = changeRequests.get(crId).orElseThrow().checks().stream().filter(c -> c.id().equals("replay")).findFirst().orElseThrow();
        assertThat(replay.status()).isEqualTo("failed");
        assertThat(replay.detail()).contains("job_2 at restart: playbook not found");
        // the published version itself has nothing older than 1.2.0 to compare with
        StepVerifier.create(handler.replayCheck("vm-restart", "1.2.0")).assertNext(r -> assertThat(r.replayed()).isZero()).verifyComplete();
        StepVerifier.create(handler.replayCheck("vm-restart", "9.9.9")).expectError(com.iaf.platform.common.domain.NotFoundException.class).verify();
    }

    @Test
    void theReplayCheckIsSkippedForAFirstVersion() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: thing-refresh, title: Refresh a thing, noun: thing, verb: refresh, owner: team-compute, version: 1.0.0 }
                workflow:
                  do:
                    - say:
                        notify: { channel: "#x", message: "hi" }
                """;
        handler.createDraft(WorkflowSource.ofYaml(yaml)).block();
        String crId = handler.publish("thing-refresh", null).block().id();
        ChangeRequest cr = changeRequests.get(crId).orElseThrow();
        assertThat(cr.checks()).filteredOn(c -> c.id().equals("replay")).extracting(Check::status).containsExactly("skipped");
        assertThat(cr.checks()).filteredOn(c -> c.id().equals("ansible-lint")).extracting(Check::status).containsExactly("skipped");
        assertThat(cr.checks()).filteredOn(c -> c.id().equals("diff")).extracting(Check::detail).first().asString().startsWith("new workflow");
        assertThat(cr.diff()).startsWith("--- /dev/null\n+++ b/workflows/team-compute/thing-refresh.yaml");
        handler.mergeChangeRequest(crId).block();
        assertThat(store.published("thing-refresh").orElseThrow().version()).isEqualTo("1.0.0");
        assertThat(eventsOf("workflow.published").get(0).data().path("previous").isNull()).as("no previous version").isTrue();
    }

    // ------------------------------------------------------------------ the releases slice talks back

    @Test
    void aRollbackRestoresThePreviousPublishedVersion() {
        handler.saveDraft("vm-restart", WorkflowSource.ofYaml(edited(vmRestart))).block();
        String crId = handler.publish("vm-restart", null).block().id();
        handler.mergeChangeRequest(crId).block();
        assertThat(store.published("vm-restart").orElseThrow().version()).isEqualTo("1.2.1");

        // the release slice's convention: from is the version being replaced, to is the candidate
        events.publish("release.rolled-back", "release", "rel_1", "platform",
                F.objectNode().put("workflow", "vm-restart").put("from", "1.2.0").put("to", "1.2.1"));

        assertThat(store.published("vm-restart").orElseThrow().version()).isEqualTo("1.2.0");
        assertThat(store.get("vm-restart", "1.2.1").orElseThrow().status()).isEqualTo(WorkflowStatus.RETIRED);
        // a second rollback of the same release finds the candidate already retired and changes nothing
        events.publish("release.rolled-back", "release", "rel_1", "platform",
                F.objectNode().put("workflow", "vm-restart").put("from", "1.2.0").put("to", "1.2.1"));
        assertThat(store.published("vm-restart").orElseThrow().version()).isEqualTo("1.2.0");
        assertThat(store.all().stream().filter(r -> r.name().equals("vm-restart") && r.isPublished()).count()).isEqualTo(1);
        events.publish("release.promoted", "release", "rel_1", "platform", F.objectNode().put("workflow", "vm-restart"));
        events.publish("release.rolled-back", "release", "rel_2", "platform", F.objectNode().put("workflow", "nope"));
        assertThat(store.published("vm-restart").orElseThrow().version()).as("nothing else changed").isEqualTo("1.2.0");
    }

    // ------------------------------------------------------------------ import

    @Test
    void importBuildsAFileFromTheRegistry() {
        StepVerifier.create(handler.importFrom(new ImportRequest("openapi", "mongodb")))
                .assertNext(i -> {
                    assertThat(WorkflowYaml.parse(i.yaml()).name()).isEqualTo("create-cluster");
                    assertThat(i.notes()).isNotEmpty();
                })
                .verifyComplete();
        StepVerifier.create(handler.importFrom(new ImportRequest("idp", "nope"))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.importFrom(new ImportRequest("svn", "x"))).expectError(IllegalArgumentException.class).verify();
    }
}

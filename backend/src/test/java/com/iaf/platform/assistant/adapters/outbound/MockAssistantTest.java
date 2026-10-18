package com.iaf.platform.assistant.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.AssistantRequest;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.Context;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.workflows.domain.CheckContext;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowChecks;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The demo agent over the repo's own sample registry: the drafts it makes must pass the real checks. */
class MockAssistantTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Identity WHO = new Identity("u-ada", "Ada", "ada@example.com", List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);

    static List<ProviderDefinition> providers;
    static List<WorkflowRecord> workflows;
    static List<ResourceTypeDefinition> types;
    static CheckContext ctx;

    @BeforeAll
    static void loadTheSampleRegistry() {
        providers = new ArrayList<>();
        workflows = new ArrayList<>();
        types = new ArrayList<>();
        Map<String, ProviderDefinition> byName = new HashMap<>();
        Set<String> names = new HashSet<>();
        Set<String> typeNames = new HashSet<>();
        for (var f : ContentFiles.scan(ROOT)) {
            switch (f.kind()) {
                case "Provider" -> {
                    ProviderDefinition p = ProviderYaml.parse(f.yaml());
                    providers.add(p);
                    byName.put(p.name(), p);
                }
                case "Workflow" -> {
                    WorkflowDefinition d = WorkflowYaml.parse(f.yaml());
                    workflows.add(WorkflowRecord.of(d, WorkflowStatus.PUBLISHED, f.relative(ROOT), NOW, "seed"));
                    names.add(d.name());
                }
                case "ResourceType" -> {
                    ResourceTypeDefinition t = ResourceTypeDefinition.parse(f.yaml());
                    types.add(t);
                    typeNames.add(t.name());
                }
                default -> {
                }
            }
        }
        ctx = CheckContext.of(byName, names, typeNames);
        assertThat(providers).isNotEmpty();
        assertThat(workflows).isNotEmpty();
        assertThat(types).isNotEmpty();
    }

    static MockAssistant agent() {
        ProviderQueries p = mock(ProviderQueries.class);
        when(p.all()).thenReturn(Flux.fromIterable(providers));
        WorkflowQueries w = mock(WorkflowQueries.class);
        when(w.publishedAll()).thenReturn(Flux.fromIterable(workflows));
        when(w.check(anyString())).thenAnswer(inv -> Mono.fromCallable(() -> WorkflowChecks.check(inv.<String>getArgument(0), ctx)));
        ResourceQueries r = mock(ResourceQueries.class);
        when(r.types()).thenReturn(Flux.fromIterable(types));
        RunsCommands runs = mock(RunsCommands.class);
        when(runs.dryRun(any(), any(), any())).thenReturn(Mono.just(new DryRunResult(true, List.of("all"), List.of(), null)));
        return new MockAssistant(p, w, r, runs, CLOCK, Duration.ZERO);
    }

    static AssistantRequest request(String text, String currentDraft) {
        AssistantSession session = new AssistantSession("asst-1", currentDraft == null ? AssistantSession.CREATE : AssistantSession.EDIT, null, WHO.id(),
                AssistantSession.WORKING, null, List.of(), null, NOW, NOW);
        return new AssistantRequest(session, text, currentDraft, WHO, new Context(null, null, "team-compute", "http://localhost:8080", List.of()));
    }

    static List<AssistantEvent> run(String text, String currentDraft) {
        List<AssistantEvent> events = agent().run(request(text, currentDraft)).collectList().block(Duration.ofSeconds(30));
        assertThat(events).isNotNull();
        return events;
    }

    static JsonNode draftOf(List<AssistantEvent> events) {
        return events.stream().filter(e -> AssistantEvent.DRAFT.equals(e.type())).reduce((a, b) -> b).map(AssistantEvent::data).orElseThrow();
    }

    static WorkflowDefinition definitionOf(List<AssistantEvent> events) {
        String yaml = draftOf(events).path("workflowYaml").asText();
        assertThat(yaml).startsWith("apiVersion:");
        return WorkflowYaml.parse(yaml);
    }

    static List<String> types(List<AssistantEvent> events) {
        return events.stream().map(AssistantEvent::type).toList();
    }

    static void assertEventSequence(List<AssistantEvent> events) {
        assertThat(types(events)).containsExactly(
                AssistantEvent.STATUS, AssistantEvent.MESSAGE, AssistantEvent.STATUS,
                AssistantEvent.DRAFT, AssistantEvent.VALIDATION, AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(events.get(0).data().path("status").asText()).isEqualTo("Reading the registry");
        assertThat(events.get(1).data().path("text").asText()).contains("provider").contains("published workflow");
        assertThat(events.get(2).data().path("status").asText()).isEqualTo("Drafting");
        assertThat(events.get(events.size() - 1).data().path("status").asText()).isEqualTo("done");
    }

    static void assertValid(List<AssistantEvent> events) {
        JsonNode draft = draftOf(events);
        assertThat(draft.path("validation").path("valid").asBoolean()).as("validation of\n" + draft.path("workflowYaml").asText() + "\n" + draft.path("validation")).isTrue();
        AssistantEvent validation = events.stream().filter(e -> AssistantEvent.VALIDATION.equals(e.type())).findFirst().orElseThrow();
        assertThat(validation.data().path("valid").asBoolean()).isTrue();
        assertThat(WorkflowChecks.check(draft.path("workflowYaml").asText(), ctx)).noneMatch(p -> p.isError());
    }

    static List<Step> providerSteps(WorkflowDefinition d, String provider) {
        return d.allSteps().stream().filter(s -> s.type() == StepType.PROVIDER && provider.equals(s.withText("provider"))).toList();
    }

    static int indexOf(WorkflowDefinition d, StepType type, String provider, String action) {
        List<Step> steps = d.allSteps();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.type() == type && (provider == null || provider.equals(s.withText("provider"))) && (action == null || action.equals(s.withText("action")))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void restartsAVmWithTheVsphereActionsAndAnApprovalBeforeTheRestart() {
        List<AssistantEvent> events = run("restart a vm after checking no backup is running, with an approval", null);
        assertEventSequence(events);
        assertValid(events);
        WorkflowDefinition d = definitionOf(events);
        assertThat(d.noun()).isEqualTo("vm");
        assertThat(d.verb()).isEqualTo("restart");
        assertThat(providerSteps(d, "vsphere").stream().map(s -> s.withText("action")))
                .containsSubsequence("vm.get", "vm.restart", "vm.waitForPowerState");
        assertThat(providerSteps(d, "backup").stream().map(s -> s.withText("action"))).contains("jobs.list");
        int approval = indexOf(d, StepType.APPROVAL, null, null);
        int restart = indexOf(d, StepType.PROVIDER, "vsphere", "vm.restart");
        int check = indexOf(d, StepType.PROVIDER, "backup", "jobs.list");
        assertThat(approval).isNotNegative();
        assertThat(check).isLessThan(approval);
        assertThat(approval).isLessThan(restart);
        assertThat(d.inputSchema().path("properties").has("vmId")).isTrue();
        assertThat(d.inputSchema().path("properties").path("vmId").path("x-resource").path("type").asText()).isEqualTo("vm");
        assertThat(d.mcpExposed()).isTrue();
        assertThat(draftOf(events).path("status").asText()).isEqualTo("ready");
        assertThat(draftOf(events).path("dryRun").path("passed").asBoolean()).isTrue();
        assertThat(d.allSteps().get(check).assertion()).contains("length == 0");
    }

    @Test
    void upgradesAMongodbClusterInProductionWithApprovalSnapshotUpgradeAndWait() {
        List<AssistantEvent> events = run("upgrade a mongodb cluster to 8.0 in production", null);
        assertEventSequence(events);
        assertValid(events);
        WorkflowDefinition d = definitionOf(events);
        assertThat(d.verb()).isEqualTo("upgrade");
        assertThat(providerSteps(d, "mongodb").stream().map(s -> s.withText("action")))
                .containsSubsequence("cluster.get", "backup.snapshot", "cluster.upgrade", "cluster.waitForState");
        int approval = indexOf(d, StepType.APPROVAL, null, null);
        int snapshot = indexOf(d, StepType.PROVIDER, "mongodb", "backup.snapshot");
        int upgrade = indexOf(d, StepType.PROVIDER, "mongodb", "cluster.upgrade");
        int wait = indexOf(d, StepType.PROVIDER, "mongodb", "cluster.waitForState");
        assertThat(approval).isNotNegative();
        assertThat(approval).isLessThan(snapshot);
        assertThat(snapshot).isLessThan(upgrade);
        assertThat(upgrade).isLessThan(wait);
        JsonNode props = d.inputSchema().path("properties");
        assertThat(props.path("targetVersion").path("default").asText()).isEqualTo("8.0");
        assertThat(props.path("environment").path("default").asText()).isEqualTo("prod");
        Step up = d.allSteps().get(upgrade);
        assertThat(up.withNode("input").toString()).contains("$input.targetVersion");
    }

    @Test
    void createAWebEnvironmentReusesThePublishedWorkflow() {
        List<AssistantEvent> events = run("create a web environment", null);
        assertEventSequence(events);
        assertValid(events);
        WorkflowDefinition d = definitionOf(events);
        List<Step> runs = d.allSteps().stream().filter(s -> s.type() == StepType.RUN).toList();
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).withText("workflow")).isEqualTo("web-environment");
        assertThat(runs.get(0).withText("version")).startsWith("^");
        assertThat(d.allSteps()).noneMatch(s -> s.type() == StepType.PROVIDER);
        assertThat(draftOf(events).path("summary").asText()).containsIgnoringCase("web environment");
    }

    @Test
    void editInsertsAnApprovalBeforeTheRestart() {
        String current = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata:
                  name: restart-vm
                  title: Restart VM
                  noun: vm
                  verb: restart
                  owner: team-compute
                  folder: Compute
                  version: 0.1.0
                api:
                  method: POST
                  path: /api/v1/vms/{vmId}:restart
                  input:
                    type: object
                    required: [vmId]
                    properties:
                      vmId: { type: string, title: VM id, x-resource: { type: vm } }
                workflow:
                  do:
                    - readVm:
                        name: Read VM
                        call: provider
                        with:
                          provider: vsphere
                          action: vm.get
                          input: { id: "{{ $input.vmId }}" }
                        output: { as: vm }
                    - restart:
                        name: Restart VM
                        call: provider
                        with:
                          provider: vsphere
                          action: vm.restart
                          input: { id: "{{ $input.vmId }}" }
                    - wait:
                        name: Wait for power on
                        call: provider
                        with:
                          provider: vsphere
                          action: vm.waitForPowerState
                          input: { id: "{{ $input.vmId }}", state: poweredOn, timeout: 600 }
                """;
        List<AssistantEvent> events = run("add an approval before the restart", current);
        assertEventSequence(events);
        assertValid(events);
        JsonNode draft = draftOf(events);
        assertThat(draft.path("changes")).isNotEmpty();
        assertThat(draft.path("changes").get(0).asText()).containsIgnoringCase("approval");
        WorkflowDefinition d = definitionOf(events);
        List<Step> steps = d.allSteps();
        int approval = indexOf(d, StepType.APPROVAL, null, null);
        int restart = indexOf(d, StepType.PROVIDER, "vsphere", "vm.restart");
        assertThat(approval).isNotNegative();
        assertThat(restart).isEqualTo(approval + 1);
        assertThat(steps.get(approval).withText("group")).isEqualTo("compute-approvers");
        assertThat(steps.stream().map(Step::id)).contains("readVm", "restart", "wait");
        assertThat(d.name()).isEqualTo("restart-vm");
    }

    @Test
    void unknownAsksBecomeQuestionsNotExceptions() {
        List<AssistantEvent> events = run("do the thing with the stuff please", null);
        assertEventSequence(events);
        JsonNode draft = draftOf(events);
        assertThat(draft.path("questions")).isNotEmpty();
        assertThat(draft.path("status").asText()).isEqualTo("needs_input");
        assertThat(draft.path("workflowYaml").asText()).contains("kind: Workflow");
        String prose = events.get(5).data().path("text").asText();
        assertThat(prose).contains("Questions:");
    }

    @Test
    void stepsForAnUnknownProductAreListedAsMissingOrAsked() {
        List<AssistantEvent> events = run("rotate the api key of the frobnicator in production", null);
        assertEventSequence(events);
        JsonNode draft = draftOf(events);
        assertThat(draft.path("workflowYaml").asText()).contains("kind: Workflow");
        assertThat(draft.path("missing").isArray()).isTrue();
        assertThat(draft.path("missing").size() + draft.path("questions").size()).isPositive();
    }

    @Test
    void draftsAProviderFromADescription() {
        ProviderDraft draft = agent().draftProvider("the backup service at https://backup.example.com with list jobs and create job", WHO)
                .block(Duration.ofSeconds(30));
        assertThat(draft).isNotNull();
        assertThat(draft.yaml()).contains("kind: Provider");
        ProviderDefinition p = ProviderYaml.parse(draft.yaml());
        assertThat(p.type()).isEqualTo(ProviderType.HTTP);
        assertThat(p.name()).contains("backup");
        assertThat(p.spec().endpoints().values()).anyMatch(url -> url.startsWith("https://backup.example.com"));
        assertThat(p.actions()).hasSize(2);
        assertThat(p.actions().stream().map(ProviderDefinition.Action::effect)).containsExactlyInAnyOrder(Effect.READ, Effect.CREATE);
        assertThat(p.actions().stream().map(ProviderDefinition.Action::id)).allMatch(id -> id.toLowerCase().contains("job"));
        assertThat(p.spec().auth().credential()).startsWith("vault:");
        assertThat(draft.summary()).isNotBlank();
        assertThat(draft.assumptions()).isNotEmpty();
        assertThat(draft.questions()).isNotEmpty();
    }

    @Test
    void askingWhatTheWorkflowDoesIsAnsweredFromTheCanvasWithoutChangingIt() {
        String current = workflows.stream().filter(w -> w.name().equals("vm-restart")).findFirst().orElseThrow().yaml();

        List<AssistantEvent> events = run("Explain what this workflow does", current);

        assertThat(types(events)).as("no draft event: the canvas keeps its draft").containsExactly(AssistantEvent.STATUS, AssistantEvent.MESSAGE, AssistantEvent.DONE);
        String text = events.get(1).data().path("text").asText();
        assertThat(text).startsWith("VM restart (vm-restart), owned by team-compute");
        assertThat(text).contains("It is started by: the API call POST /api/v1/vms/{vmId}:restart").contains("an alert from grafana");
        assertThat(text).contains("1. Check VM exists: calls the vsphere provider's vm.get").contains("waits for an approval by compute-approvers");
        assertThat(text).contains("It returns vmName and powerState.");
        assertThat(text).doesNotContain("I could not apply");
        assertThat(events.get(2).data().path("status").asText()).isEqualTo("done");

        List<AssistantEvent> nothing = run("describe it", null);
        assertThat(types(nothing)).containsExactly(AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(nothing.get(0).data().path("text").asText()).isEqualTo(MockAssistant.NOTHING_TO_EXPLAIN);

        List<AssistantEvent> broken = run("summarise this workflow", "kind: [not, yaml");
        assertThat(types(broken)).containsExactly(AssistantEvent.STATUS, AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(broken.get(1).data().path("text").asText()).startsWith("The draft on the canvas does not parse");

        // on an empty canvas, a request with something to build is drafted
        List<AssistantEvent> create = run("Describe a workflow that restarts a VM", null);
        assertThat(types(create)).contains(AssistantEvent.DRAFT);
        assertThat(definitionOf(create).verb()).isEqualTo("restart");
    }

    @Test
    void modeIsMock() {
        assertThat(agent().mode()).isEqualTo("mock");
    }
}

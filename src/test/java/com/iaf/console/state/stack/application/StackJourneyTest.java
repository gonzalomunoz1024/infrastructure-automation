package com.iaf.console.state.stack.application;

import static com.iaf.console.state.inventory.domain.Fixtures.ACCOUNT;
import static com.iaf.console.state.inventory.domain.Fixtures.REGION;
import static com.iaf.console.state.inventory.domain.Fixtures.T0;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.outbox.InMemoryOutbox;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.TestClock;
import com.iaf.console.state.inventory.adapters.memory.InMemoryAccountKeys;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceEventStore;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceProjection;
import com.iaf.console.state.inventory.application.InventoryMetrics;
import com.iaf.console.state.inventory.application.InventoryService;
import com.iaf.console.state.inventory.application.ResourceEventPublisher;
import com.iaf.console.state.inventory.application.ResourceLoader;
import com.iaf.console.state.inventory.application.SensitiveData;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Fixtures;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceState;
import com.iaf.console.state.inventory.domain.ResourceTypeInfo;
import com.iaf.console.state.inventory.domain.StackRef;
import com.iaf.console.state.inventory.ports.outbound.ResourceTypesPort;
import com.iaf.console.state.stack.adapters.memory.InMemoryStackEvents;
import com.iaf.console.state.stack.adapters.memory.InMemoryStacks;
import com.iaf.console.state.stack.domain.BundledBlueprints;
import com.iaf.console.state.stack.domain.ReadyStep;
import com.iaf.console.state.stack.domain.Stack;
import com.iaf.console.state.stack.domain.StackEvent;
import com.iaf.console.state.stack.domain.StackOperation;
import com.iaf.console.state.stack.domain.StackState;
import com.iaf.console.state.stack.domain.StackStep;
import com.iaf.console.state.stack.domain.StepAction;
import com.iaf.console.state.stack.domain.StepState;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A stack's life on in-memory stores, driven as the BFF and the platform feed drive it (hub
 * decision 33): launched from the bundled dev-sandbox blueprint, its children minted against their
 * steps and registered, torn down in reverse with the volume retained, a failed delete resumed, and a
 * failed create retried.
 */
class StackJourneyTest {

    private static final AccountScope HERE = AccountScope.of(ACCOUNT, REGION, "dana");
    private static final ResourceTypeInfo VOLUME = new ResourceTypeInfo("volume", "volumes", "storage", Map.of(), "missing", Set.of(), Set.of(),
            Set.of());

    private final TestClock clock = new TestClock(T0);
    private final InMemoryResourceEventStore events = new InMemoryResourceEventStore();
    private final InMemoryResourceProjection projection = new InMemoryResourceProjection();
    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final ResourceLoader loader = new ResourceLoader(events, projection);
    private final ResourceTypesPort types = new ResourceTypesPort() {
        @Override
        public Mono<ResourceTypeInfo> find(String name) {
            return switch (name) {
                case "vm" -> Mono.just(Fixtures.VM);
                case "volume" -> Mono.just(VOLUME);
                default -> Mono.empty();
            };
        }

        @Override
        public Flux<ResourceTypeInfo> list() {
            return Flux.just(Fixtures.VM, VOLUME);
        }
    };
    private final InMemoryStacks store = new InMemoryStacks();
    private final InMemoryStackEvents log = new InMemoryStackEvents();
    private final Stacks stacks = new Stacks(store, log, new BlueprintCatalog(List.of(BundledBlueprints.bundled("dev-sandbox"))), loader, types,
            new StackEventPublisher(outbox), clock, "wf", new Stacks.Settings(Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofDays(90)));
    private final InventoryService inventory = new InventoryService(loader, events, projection, types,
            new SensitiveData(new InMemoryAccountKeys(), SensitiveData.randomMasterKey(), clock), new ResourceEventPublisher(outbox),
            new InventoryMetrics(new SimpleMeterRegistry()), clock, "wf", Duration.ofDays(90), stacks);
    private final StackQueries queries = new StackQueries(store, log);
    private final StackSweeper sweeper = new StackSweeper(stacks, store, 100);

    private Stack launch(String name) {
        ObjectNode parameters = Json.object().put("image", "img-golden-1");
        return stacks.launch(HERE, new Stacks.Launch("dev-sandbox", null, name, parameters, Map.of("team", "payments"), "dana", "req_launch")).block();
    }

    private Stack stack(Stack s) {
        return queries.get(HERE, s.wfrn()).block();
    }

    private static StackStep step(Stack s, String id) {
        return s.step(id).orElseThrow();
    }

    /** What the BFF does with a ready create: the mint naming the step, then the platform's job. */
    private Resource mint(Stack s, String step) {
        ReadyStep ready = step(stack(s), step).ready();
        return inventory.mint(HERE, new Commands.Mint(ready.type(), REGION, ready.input().path("name").asText(null), "create-" + ready.type(),
                ready.input(), by("dana"), new StackRef(s.wfrn(), step, ready.attempt()))).block();
    }

    private Resource register(Resource r, String registryId) {
        return inventory.registered(HERE, r.wfrn(), new Commands.Registration("job-" + registryId, "create-" + r.type(), "1.0.0", registryId,
                "provider-" + registryId, r.name(), Json.object().put("note", registryId), by("platform"))).block();
    }

    private Resource requestDelete(String wfrn, String job) {
        return inventory.requestDelete(HERE, wfrn, new Commands.RequestDelete(job, "delete-vm", "1.0.0", "torn down with its stack", by("dana")))
                .block();
    }

    private List<String> stackEvents(Stack s) {
        return queries.events(HERE, s.wfrn(), 0, 100).block().items().stream().map(e -> e.type().wire()).toList();
    }

    @Test
    void aStackLaunchesInDependencyOrderAndResolvesEachStepFromWhatCameBefore() {
        Stack s = launch("sbx");
        assertThat(s.state()).isEqualTo(StackState.PENDING);
        assertThat(s.wfrn()).matches("wfrn:wf:blueprints:global:" + ACCOUNT + ":stack/stack-[0-9a-z]+");
        assertThat(step(s, "vm").state()).isEqualTo(StepState.READY);
        assertThat(step(s, "scratch").state()).as("waits for the vm's registry id").isEqualTo(StepState.WAITING);
        ReadyStep first = step(s, "vm").ready();
        assertThat(first.input().toString()).isEqualTo("{\"name\":\"sbx-vm\",\"image\":\"img-golden-1\",\"size\":\"small\",\"environment\":\"dev\"}");
        assertThat(first.idempotencyKey()).isEqualTo(s.wfrn().substring(s.wfrn().lastIndexOf('/') + 1) + ":vm:create:1");
        assertThat(first.service()).isEqualTo("compute");

        ReadyStep announced = step(stack(s), "vm").ready();
        Resource vm = mint(s, "vm");
        assertThat(vm.stack().wfrn()).isEqualTo(s.wfrn());
        assertThat(inventory.mint(HERE, new Commands.Mint("vm", REGION, "sbx-vm", "create-vm", announced.input(), by("dana"),
                new StackRef(s.wfrn(), "vm", 1))).block().wfrn()).as("the same mint again answers the same child").isEqualTo(vm.wfrn());
        assertThat(step(stack(s), "vm").state()).isEqualTo(StepState.RUNNING);
        assertThat(step(stack(s), "vm").wfrn()).isEqualTo(vm.wfrn());

        register(vm, "vm-101");
        Stack afterVm = stack(s);
        assertThat(step(afterVm, "vm").state()).isEqualTo(StepState.DONE);
        assertThat(step(afterVm, "scratch").state()).isEqualTo(StepState.READY);
        assertThat(step(afterVm, "scratch").ready().input().toString()).as("a whole-value reference keeps its type")
                .isEqualTo("{\"vmId\":\"vm-101\",\"sizeGb\":50}");

        register(mint(s, "scratch"), "vol-7");
        Stack active = stack(s);
        assertThat(active.state()).isEqualTo(StackState.ACTIVE);
        assertThat(active.outputs().get("vm").value()).isEqualTo(vm.wfrn());
        assertThat(stackEvents(s)).containsExactly("StackRequested", "StackStepStarted", "StackStepSucceeded", "StackStepStarted",
                "StackStepSucceeded", "StackLaunched");
        assertThat(outbox.all().filter(e -> e.topic().equals(StackEventPublisher.TOPIC)).count().block()).isEqualTo(6L);
    }

    @Test
    void aStepTakesOneChildPerAttemptAndAStacksChildStaysInItsAccount() {
        Stack s = launch("sbx");
        assertThatThrownBy(() -> inventory.mint(HERE, new Commands.Mint("volume", REGION, "early", "create-volume", Json.object(), by("dana"),
                new StackRef(s.wfrn(), "scratch", 1))).block()).isInstanceOf(ConflictException.class).hasMessageStartingWith("stack.stepNotReady");
        assertThatThrownBy(() -> inventory.mint(HERE, new Commands.Mint("volume", REGION, "wrong", "create-volume", Json.object(), by("dana"),
                new StackRef(s.wfrn(), "vm", 1))).block()).isInstanceOf(InvalidInputException.class);
        Resource vm = register(mint(s, "vm"), "vm-101");

        assertThatThrownBy(() -> inventory.transfer(HERE, vm.wfrn(), new Commands.Transfer("210987654321", "the team moved", by("dana"))).block())
                .isInstanceOf(ConflictException.class).hasMessageStartingWith("resource.inStack");
    }

    @Test
    void aTeardownDeletesInReverseOrderRetainsWhatItKeepsAndResumesAFailedDelete() {
        Stack s = launch("sbx");
        Resource vm = register(mint(s, "vm"), "vm-101");
        Resource scratch = register(mint(s, "scratch"), "vol-7");

        assertThatThrownBy(() -> stacks.teardown(HERE, s.wfrn(), "short", List.of(), "dana", "req_t").block())
                .isInstanceOf(InvalidInputException.class);
        Stack deleting = stacks.teardown(HERE, s.wfrn(), "the sandbox is no longer needed", List.of(), "dana", "req_t").block();
        assertThat(deleting.state()).isEqualTo(StackState.DELETING);
        assertThat(deleting.operation()).isEqualTo(StackOperation.TEARDOWN);
        assertThat(step(deleting, "scratch").state()).as("deletionPolicy retain").isEqualTo(StepState.RETAINED);
        ReadyStep delete = step(deleting, "vm").ready();
        assertThat(delete.action()).isEqualTo(StepAction.DELETE);
        assertThat(delete.wfrn()).isEqualTo(vm.wfrn());
        assertThat(delete.input().toString())
                .isEqualTo("{\"reason\":\"Sandbox sbx torn down: the sandbox is no longer needed\",\"keepSnapshotDays\":0}");

        requestDelete(vm.wfrn(), "job-del-1");
        assertThat(step(stack(s), "vm").state()).isEqualTo(StepState.DELETING);
        inventory.jobFailed(HERE, vm.wfrn(), new Commands.JobFailed("job-del-1", "delete-vm", "1.0.0", "failed", "Timeout", "power-off",
                "the hypervisor timed out", by("platform"))).block();
        Stack failed = stack(s);
        assertThat(step(failed, "vm").state()).isEqualTo(StepState.FAILED);
        assertThat(step(failed, "vm").error().code()).isEqualTo("delete.failed");
        assertThat(failed.state()).as("a teardown stays one; the failure says what stopped it").isEqualTo(StackState.DELETING);
        assertThat(failed.failure().step()).isEqualTo("vm");

        Stack resumed = stacks.resume(HERE, s.wfrn(), "dana", "req_r").block();
        assertThat(step(resumed, "vm").ready().attempt()).isEqualTo(2);
        assertThat(resumed.failure()).isNull();
        requestDelete(vm.wfrn(), "job-del-2");
        inventory.deleted(HERE, vm.wfrn(), new Commands.Deletion("job-del-2", "vm-101", by("platform"))).block();

        Stack gone = stack(s);
        assertThat(gone.state()).isEqualTo(StackState.DELETED);
        assertThat(gone.deletedAt()).isEqualTo(clock.instant());
        assertThat(step(gone, "vm").state()).isEqualTo(StepState.DELETED);
        assertThat(inventory.current(HERE, scratch.wfrn()).block().state()).as("the retained volume stays").isEqualTo(ResourceState.ACTIVE);
        assertThatThrownBy(() -> stacks.resume(HERE, s.wfrn(), "dana", "req_r2").block()).isInstanceOf(ConflictException.class);
    }

    @Test
    void aTeardownCanRetainAStepThatWouldBeDeleted() {
        Stack s = launch("sbx");
        Resource vm = register(mint(s, "vm"), "vm-101");
        register(mint(s, "scratch"), "vol-7");

        Stack gone = stacks.teardown(HERE, s.wfrn(), "keep the vm, drop the stack", List.of("vm"), "dana", "req_t").block();
        assertThat(gone.state()).isEqualTo(StackState.DELETED);
        assertThat(step(gone, "vm").state()).isEqualTo(StepState.RETAINED);
        assertThat(inventory.transfer(HERE, vm.wfrn(), new Commands.Transfer("210987654321", "the team moved", by("dana"))).block().accountId())
                .as("a retained resource leaves the stack").isEqualTo("210987654321");
    }

    @Test
    void aFailedChildStopsTheLaunchAndItsRetryResumesIt() {
        Stack s = launch("sbx");
        Resource vm = mint(s, "vm");
        inventory.jobFailed(HERE, vm.wfrn(), new Commands.JobFailed("job-1", "create-vm", "1.0.0", "failed", "Quota", "allocate", "no capacity",
                by("platform"))).block();
        Stack failed = stack(s);
        assertThat(failed.state()).isEqualTo(StackState.FAILED);
        assertThat(failed.failure().step()).isEqualTo("vm");
        assertThat(step(failed, "scratch").state()).isEqualTo(StepState.WAITING);

        inventory.requestRetry(HERE, vm.wfrn(), new Commands.RequestRetry("job-2", "create-vm", "1.0.0", by("dana"))).block();
        assertThat(step(stack(s), "vm").state()).isEqualTo(StepState.RUNNING);
        register(vm, "vm-101");
        assertThat(step(stack(s), "scratch").state()).isEqualTo(StepState.READY);
        assertThat(stack(s).state()).isEqualTo(StackState.PENDING);
    }

    @Test
    void aStepTheBffCouldNotStartFailsAndResumesAtTheNextAttempt() {
        Stack s = launch("sbx");
        Stack failed = stacks.reportFailure(HERE, s.wfrn(), "vm", 1, "create", "authz.denied", "dana may not create a vm", "dana", "req_f").block();
        assertThat(step(failed, "vm").state()).isEqualTo(StepState.FAILED);
        assertThat(stacks.reportFailure(HERE, s.wfrn(), "vm", 1, "create", "authz.denied", "again", "dana", "req_f").block().version())
                .as("the same report again changes nothing").isEqualTo(failed.version());
        assertThatThrownBy(() -> stacks.reportFailure(HERE, s.wfrn(), "scratch", 1, "create", null, null, "dana", "req_f").block())
                .isInstanceOf(ConflictException.class);

        Stack resumed = stacks.resume(HERE, s.wfrn(), "dana", "req_r").block();
        assertThat(step(resumed, "vm").state()).isEqualTo(StepState.READY);
        assertThat(step(resumed, "vm").ready().attempt()).isEqualTo(2);
        assertThat(step(resumed, "vm").ready().idempotencyKey()).endsWith(":vm:create:2");
        assertThatThrownBy(() -> inventory.mint(HERE, new Commands.Mint("vm", REGION, "old", "create-vm", Json.object(), by("dana"),
                new StackRef(s.wfrn(), "vm", 1))).block()).as("the old attempt takes nothing").isInstanceOf(ConflictException.class);
        Resource second = mint(s, "vm");
        assertThat(step(stack(s), "vm").wfrn()).isEqualTo(second.wfrn());
        assertThat(step(stack(s), "vm").attempt()).isEqualTo(2);
    }

    @Test
    void theSweeperAnnouncesAgainAStepNobodyStartedAndFailsAChildNeverRecorded() {
        Stack s = launch("sbx");
        long before = outbox.all().count().block();
        clock.advance(Duration.ofMinutes(6));
        assertThat(sweeper.tick().block()).isEqualTo(1);
        List<StackEvent> log = queries.events(HERE, s.wfrn(), 0, 100).block().items();
        assertThat(log.get(log.size() - 1).type().wire()).isEqualTo("StackResumed");
        assertThat(outbox.all().count().block()).isEqualTo(before + 1);
        sweeper.tick().block();
        assertThat(outbox.all().count().block()).as("not again before another five minutes").isEqualTo(before + 1);

        stacks.claim(HERE, new StackRef(s.wfrn(), "vm", 1), "vm", REGION, "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-lost", "dana", "req_m")
                .block();
        clock.advance(Duration.ofMinutes(11));
        sweeper.tick().block();
        assertThat(step(stack(s), "vm").error().code()).isEqualTo("child.missing");
        assertThat(stack(s).state()).isEqualTo(StackState.FAILED);
    }

    @Test
    void theParametersAreCheckedAndTheNameIsUniqueAmongLiveStacks() {
        assertThatThrownBy(() -> stacks.launch(HERE, new Stacks.Launch("dev-sandbox", null, "sbx", Json.object(), Map.of(), "dana", "r")).block())
                .as("image is required").isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> stacks.launch(HERE, new Stacks.Launch("dev-sandbox", null, "sbx",
                Json.object().put("image", "img-1").put("scratchGb", 5), Map.of(), "dana", "r")).block()).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> stacks.launch(HERE, new Stacks.Launch("dev-sandbox", "0.9.0", "sbx", Json.object().put("image", "img-1"), Map.of(),
                "dana", "r")).block()).isInstanceOf(ConflictException.class).hasMessageStartingWith("blueprint.versionChanged");
        assertThatThrownBy(() -> launch("No_Caps")).isInstanceOf(InvalidInputException.class);
        launch("sbx");
        assertThatThrownBy(() -> launch("sbx")).isInstanceOf(ConflictException.class);
    }

    @Test
    void aPlanShowsTheWavesAndWhatTheBffAuthorizesWithNothingChanged() {
        Stacks.Plan plan = stacks.plan(HERE, "dev-sandbox", "sbx", Json.object().put("image", "img-1")).block();
        assertThat(plan.steps()).extracting(Stacks.PlannedStep::step, Stacks.PlannedStep::wave).containsExactly(
                org.assertj.core.groups.Tuple.tuple("vm", 0), org.assertj.core.groups.Tuple.tuple("scratch", 1));
        assertThat(plan.steps().get(0).input().path("name").asText()).isEqualTo("sbx-vm");
        assertThat(plan.steps().get(1).input().path("vmId").asText()).isEqualTo("${resources.vm.registryId}");
        assertThat(plan.authorize()).extracting(Stacks.Authorization::action).containsExactly("blueprints:launch", "compute:create",
                "storage:create");
        assertThat(store.list(HERE, new com.iaf.console.state.stack.domain.StackQuery(null, null, null, 50, null)).block().items()).isEmpty();
    }
}

package com.iaf.console.state.inventory.application;

import static com.iaf.console.state.inventory.domain.Fixtures.ACCOUNT;
import static com.iaf.console.state.inventory.domain.Fixtures.REGION;
import static com.iaf.console.state.inventory.domain.Fixtures.T0;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.idempotency.IdempotentConsumer;
import com.iaf.console.kit.idempotency.InMemoryProcessedEvents;
import com.iaf.console.kit.outbox.InMemoryOutbox;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.platform.model.JobContext;
import com.iaf.console.platform.model.ResourceOrigin;
import com.iaf.console.state.TestClock;
import com.iaf.console.state.inventory.adapters.memory.InMemoryAccountKeys;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceEventStore;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceProjection;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Fixtures;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceDecider;
import com.iaf.console.state.inventory.domain.ResourceFold;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceState;
import com.iaf.console.state.inventory.domain.ResourceTypeInfo;
import com.iaf.console.state.inventory.domain.TombstoneException;
import com.iaf.console.state.inventory.ports.outbound.ResourceTypesPort;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Moving a resource to another account by re-import, the old name kept as an alias of the new (WP 7.7). */
class TransferTest {

    private static final String OTHER = "210987654321";
    private static final String THIRD = "345678901234";
    private static final AccountScope HERE = AccountScope.of(ACCOUNT, REGION, "dana");
    private static final AccountScope THERE = AccountScope.of(OTHER, REGION, "dana");

    private final TestClock clock = new TestClock(T0);
    private final InMemoryResourceEventStore events = new InMemoryResourceEventStore();
    private final InMemoryResourceProjection projection = new InMemoryResourceProjection();
    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final InventoryMetrics metrics = new InventoryMetrics(new SimpleMeterRegistry());
    private final PlatformPort platform = mock(PlatformPort.class, withSettings().defaultAnswer(emptyPublishers()));
    private final ResourceTypesPort types = new ResourceTypesPort() {
        @Override
        public Mono<ResourceTypeInfo> find(String name) {
            return "vm".equals(name) ? Mono.just(Fixtures.VM) : Mono.empty();
        }

        @Override
        public Flux<ResourceTypeInfo> list() {
            return Flux.just(Fixtures.VM);
        }
    };
    private final SensitiveData sensitive = new SensitiveData(new InMemoryAccountKeys(), SensitiveData.randomMasterKey(), clock);
    private final InventoryService inventory = new InventoryService(new ResourceLoader(events, projection), events, projection, types, sensitive,
            new ResourceEventPublisher(outbox), metrics, clock, "wf", Duration.ofDays(90));
    private final PlatformResources registry = new PlatformResources(platform);
    private final JobOutcomes outcomes = new JobOutcomes(inventory, registry, platform);
    private final PlatformEventHandler handler = new PlatformEventHandler(inventory, outcomes, registry,
            new IdempotentConsumer(new InMemoryProcessedEvents(), clock), metrics, REGION);
    private final PendingReaper reaper = new PendingReaper(projection, platform, inventory, outcomes, registry, metrics, clock, Duration.ofMinutes(5),
            Duration.ofHours(2));

    private static Answer<Object> emptyPublishers() {
        return invocation -> {
            Class<?> returned = invocation.getMethod().getReturnType();
            if (returned == Mono.class) {
                return Mono.empty();
            }
            if (returned == Flux.class) {
                return Flux.empty();
            }
            return null;
        };
    }

    private Resource imported(String name) {
        String wfrn = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-" + name;
        ObjectNode attributes = Fixtures.attributes(name, 2, 4).put("adminPassword", "s3cret-" + name);
        Resource r = inventory.importResource(HERE, new Commands.Import("vm", REGION, wfrn, "id-" + name, "vsphere-" + name, name, attributes,
                "backfill", by("import"), List.of("img-1"))).block();
        return inventory.tag(HERE, r.wfrn(), new Commands.Tag(Map.of("owner-team", "payments"), List.of(), by("dana"))).block();
    }

    private Commands.Transfer to(String account) {
        return new Commands.Transfer(account, "the team moved", by("dana"));
    }

    private List<String> published() {
        return outbox.all().map(e -> e.event().type()).collectList().block();
    }

    @Test
    void aResourceMovesToAnotherAccountByReImportAndItsOldNameStaysAnAlias() {
        Resource before = imported("web-01");
        int already = published().size();

        Resource moved = inventory.transfer(HERE, before.wfrn(), to(OTHER)).block();

        ResourceIdentity there = ResourceIdentity.of(moved.wfrn());
        assertThat(there.accountId()).isEqualTo(OTHER);
        assertThat(there.type()).isEqualTo("vm");
        assertThat(moved.wfrn()).isNotEqualTo(before.wfrn());
        assertThat(moved.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(moved.version()).isEqualTo(1);
        assertThat(moved.source()).isEqualTo("transfer");
        assertThat(moved.transferredFrom()).isEqualTo(before.wfrn());
        assertThat(moved.registryId()).isEqualTo("id-web-01");
        assertThat(moved.providerId()).isEqualTo("vsphere-web-01");
        assertThat(moved.name()).isEqualTo("web-01");
        assertThat(moved.tags()).containsEntry("owner-team", "payments");
        assertThat(moved.builtFrom()).containsExactly("img-1");
        assertThat(published().subList(already, published().size())).containsExactly("Transferred", "Imported");

        // the secret moved sealed under the new account's key, and opens there
        assertThat(SensitiveData.isSealed(moved.attributes().get("adminPassword"))).isTrue();
        assertThat(sensitive.reveal(moved).block().attributes().get("adminPassword").asText()).isEqualTo("s3cret-web-01");

        Resource alias = inventory.current(HERE, before.wfrn()).block();
        assertThat(alias.state()).isEqualTo(ResourceState.DELETED);
        assertThat(alias.transferredTo()).isEqualTo(moved.wfrn());
        assertThat(alias.tombstoneUntil()).as("an alias is kept for good").isNull();
        assertThat(alias.tombstone().error()).isEqualTo("resource.transferred");
        assertThat(alias.tombstone().transferredTo()).isEqualTo(moved.wfrn());
        assertThatThrownBy(() -> inventory.tag(HERE, before.wfrn(), new Commands.Tag(Map.of("a", "b"), List.of(), by("dana"))).block())
                .isInstanceOfSatisfying(TombstoneException.class, e -> assertThat(e.tombstone().transferredTo()).isEqualTo(moved.wfrn()));
        assertThatThrownBy(() -> inventory.importResource(HERE, new Commands.Import("vm", REGION, before.wfrn(), "id-web-01-again", "vsphere-web-01",
                "web-01", Fixtures.attributes("web-01", 2, 4), "backfill", by("import"))).block())
                .as("a moved name never comes back").isInstanceOf(TombstoneException.class);

        // the same move again changes nothing and answers where it went; another destination is refused
        assertThat(inventory.transfer(HERE, before.wfrn(), to(OTHER)).block().wfrn()).isEqualTo(moved.wfrn());
        assertThat(published()).hasSize(already + 2);
        assertThatThrownBy(() -> inventory.transfer(HERE, before.wfrn(), to(THIRD)).block()).isInstanceOf(TombstoneException.class);

        // and it can move on: the new name answers for itself
        Resource onward = inventory.transfer(THERE, moved.wfrn(), to(THIRD)).block();
        assertThat(onward.transferredFrom()).isEqualTo(moved.wfrn());
        assertThat(sensitive.reveal(onward).block().attributes().get("adminPassword").asText()).isEqualTo("s3cret-web-01");
    }

    @Test
    void onlyAnIdleActiveResourceMovesAndOnlyToAnotherAccount() {
        Resource pending = inventory.mint(HERE, new Commands.Mint("vm", REGION, "web-02", "create-vm", Fixtures.input("web-02"), by("dana"))).block();
        assertThatThrownBy(() -> inventory.transfer(HERE, pending.wfrn(), to(OTHER)).block())
                .isInstanceOf(ConflictException.class).hasMessageStartingWith("resource.busy");
        Resource active = imported("web-03");
        assertThatThrownBy(() -> inventory.transfer(HERE, active.wfrn(), to(ACCOUNT)).block()).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> inventory.transfer(HERE, active.wfrn(), to("nope")).block()).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> inventory.transfer(THERE, active.wfrn(), to(THIRD)).block())
                .as("another account's resource").isInstanceOf(com.iaf.console.kit.errors.NotFoundException.class);
        assertThatThrownBy(() -> inventory.transfer(HERE, active.wfrn(), new Commands.Transfer(OTHER, null,
                new Commands.Meta(1L, "dana", null))).block()).isInstanceOf(ConflictException.class).hasMessageStartingWith("version");
        assertThat(inventory.current(HERE, active.wfrn()).block().state()).isEqualTo(ResourceState.ACTIVE);
    }

    @Test
    void anAccountMovesWholeAndWhatIsBusyStays() {
        imported("a-01");
        imported("a-02");
        imported("a-03");
        Resource busy = inventory.mint(HERE, new Commands.Mint("vm", REGION, "a-04", "create-vm", Fixtures.input("a-04"), by("dana"))).block();

        InventoryService.AccountTransfer first = inventory.transferAccount(HERE, OTHER, "reorg", "svc:bff", 2).block();
        assertThat(first.transferred()).isEqualTo(2);
        assertThat(first.moved()).allSatisfy(m -> {
            assertThat(ResourceIdentity.of(m.to()).accountId()).isEqualTo(OTHER);
            assertThat(inventory.current(HERE, m.from()).block().transferredTo()).isEqualTo(m.to());
            assertThat(m.registryId()).startsWith("id-a-0");
        });
        assertThat(first.more()).isTrue();
        InventoryService.AccountTransfer second = inventory.transferAccount(HERE, OTHER, "reorg", "svc:bff", 2).block();
        assertThat(second.transferred()).isEqualTo(1);
        assertThat(second.more()).isFalse();
        assertThat(second.skipped()).extracting(InventoryService.AccountTransfer.Skipped::wfrn).containsExactly(busy.wfrn());
        assertThat(second.skipped()).extracting(InventoryService.AccountTransfer.Skipped::reason).containsExactly("resource.busy");
        assertThat(projection.list(THERE, com.iaf.console.state.inventory.domain.ResourceQuery.all()).block().items()).hasSize(3)
                .allSatisfy(r -> assertThat(r.source()).isEqualTo("transfer"));
    }

    @Test
    void theFeedFollowsAMovedNameToTheResourceItBecame() {
        Resource before = imported("web-05");
        Resource moved = inventory.transfer(HERE, before.wfrn(), to(OTHER)).block();

        // the platform has not re-homed its record yet: it still speaks of the old name and account
        com.iaf.console.platform.model.Resource record = new com.iaf.console.platform.model.Resource("id-web-05", "vm", "web-05", "ready",
                Map.of("name", "web-05", "cpu", 8, "memoryGb", 16, "environment", "dev"), "team-compute", "team",
                new ResourceOrigin("job_r", "resize-vm", "1.0.0"), List.of("img-1"), T0, T0, before.wfrn(), ACCOUNT, REGION, "vsphere-web-05", null);
        when(platform.getResourceByWfrn(moved.wfrn())).thenReturn(Mono.empty());
        when(platform.getResource("id-web-05")).thenReturn(Mono.just(record));
        ObjectNode registered = Json.object().put("id", "id-web-05").put("type", "vm").put("state", "ready").put("jobId", "job_r")
                .put("workflow", "resize-vm").put("by", "platform").put("wfrn", before.wfrn()).put("accountId", ACCOUNT).put("region", REGION);
        handler.handle(feed(1, "resource.registered", "id-web-05", null, registered)).block();
        Resource resized = inventory.current(THERE, moved.wfrn()).block();
        assertThat(resized.attributes().get("cpu").asInt()).isEqualTo(8);
        assertThat(inventory.current(HERE, before.wfrn()).block().version()).as("the alias is left alone").isEqualTo(before.version() + 1);

        ObjectNode retired = Json.object().put("id", "id-web-05").put("type", "vm").put("by", "platform").put("wfrn", before.wfrn())
                .put("accountId", ACCOUNT).put("region", REGION).put("jobId", "job_9");
        handler.handle(feed(2, "resource.retired", "id-web-05", null, retired)).block();
        assertThat(inventory.current(THERE, moved.wfrn()).block().state()).isEqualTo(ResourceState.DELETED);
    }

    @Test
    void theReaperFinishesAMoveCutShortAfterItsTombstone() {
        Resource before = imported("web-06");
        // the tombstone was written and the call ended before the second half
        ResourceIdentity there = ResourceIdentity.mint("wf", "compute", REGION, OTHER, "vm");
        var transferred = ResourceDecider.transfer(before, to(OTHER), there, clock.instant());
        var stored = events.append(transferred).block();
        projection.upsert(ResourceFold.apply(before, stored)).block();
        assertThat(inventory.current(THERE, there.wfrn()).block()).isNull();

        clock.advance(Duration.ofSeconds(30));
        reaper.tick().block();
        assertThat(inventory.current(THERE, there.wfrn()).block()).as("left alone while it may still be in flight").isNull();

        clock.advance(Duration.ofMinutes(2));
        reaper.tick().block();
        Resource finished = inventory.current(THERE, there.wfrn()).block();
        assertThat(finished.transferredFrom()).isEqualTo(before.wfrn());
        assertThat(finished.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(sensitive.reveal(finished).block().attributes().get("adminPassword").asText()).isEqualTo("s3cret-web-06");

        reaper.tick().block();
        assertThat(events.findEvents(THERE, there.wfrn(), 0, 10).collectList().block()).as("finished once").hasSize(1);
    }

    private static FeedEvent feed(long seq, String type, String aggregateId, String wfrn, ObjectNode data) {
        JobContext context = wfrn == null ? null : new JobContext(ACCOUNT, REGION, wfrn, "req_" + seq, Map.of());
        return new FeedEvent("1.0", 1, "evt_" + seq, type + ":" + aggregateId + ":" + seq, seq, type, T0.plusSeconds(seq), "resource", aggregateId,
                "svc:platform", null, context, null, data);
    }
}

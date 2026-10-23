package com.iaf.console.state.inventory.application;

import static com.iaf.console.state.inventory.domain.Fixtures.REGION;
import static com.iaf.console.state.inventory.domain.Fixtures.T0;
import static com.iaf.console.state.inventory.domain.Fixtures.by;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.outbox.InMemoryOutbox;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.TestClock;
import com.iaf.console.state.inventory.adapters.memory.InMemoryAccountKeys;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceEventStore;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceProjection;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Fixtures;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceTypeInfo;
import com.iaf.console.state.inventory.ports.outbound.ResourceTypesPort;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The inventory on in-memory stores and a hand-moved clock, for the tests of the reads built on it. */
final class InventoryHarness {

    final TestClock clock = new TestClock(T0);
    final InMemoryResourceEventStore events = new InMemoryResourceEventStore();
    final InMemoryResourceProjection projection = new InMemoryResourceProjection();
    final ResourceLoader loader = new ResourceLoader(events, projection);
    final SensitiveData sensitive = new SensitiveData(new InMemoryAccountKeys(), SensitiveData.randomMasterKey(), clock);
    final ResourceTypesPort types = new ResourceTypesPort() {
        @Override
        public Mono<ResourceTypeInfo> find(String name) {
            return switch (name) {
                case "vm" -> Mono.just(Fixtures.VM);
                case "database" -> Mono.just(Fixtures.DATABASE);
                default -> Mono.empty();
            };
        }

        @Override
        public Flux<ResourceTypeInfo> list() {
            return Flux.just(Fixtures.VM, Fixtures.DATABASE);
        }
    };
    final InventoryService inventory = new InventoryService(loader, events, projection, types, sensitive,
            new ResourceEventPublisher(new InMemoryOutbox()), new InventoryMetrics(new SimpleMeterRegistry()), clock, "wf", Duration.ofDays(90));
    final InventoryQueries queries = new InventoryQueries(loader, projection, events, sensitive);
    final Relationships relationships = new Relationships(loader, projection);

    static AccountScope scope(String account) {
        return AccountScope.of(account, REGION, "dana");
    }

    /** Imports a resource of an account with a registry id, built from other registry ids. */
    Resource imported(String account, String service, String type, String name, ObjectNode attributes, String... builtFrom) {
        String wfrn = ResourceIdentity.mint("wf", service, REGION, account, type).wfrn();
        return inventory.importResource(scope(account), new Commands.Import(type, REGION, wfrn, type + "-" + name, "provider-" + name, name, attributes,
                "backfill", by("import"), List.of(builtFrom))).block();
    }
}

package com.iaf.console.state.load;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.adapters.mongo.MongoAccountKeys;
import com.iaf.console.state.inventory.adapters.mongo.MongoResourceEventStore;
import com.iaf.console.state.inventory.adapters.mongo.MongoResourceProjection;
import com.iaf.console.state.inventory.application.InventoryMetrics;
import com.iaf.console.state.inventory.application.InventoryService;
import com.iaf.console.state.inventory.application.ResourceEventPublisher;
import com.iaf.console.state.inventory.application.ResourceLoader;
import com.iaf.console.state.inventory.application.SensitiveData;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceTypeInfo;
import com.iaf.console.state.inventory.ports.outbound.ResourceTypesPort;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Fills the state service's MongoDB with the {@link StateEstate} (WP 6.2) through the service's own
 * inventory and adapters, so the events, the projection and its indexes are the ones the service
 * writes: each resource is imported ({@code backfill}) and tagged, two events. Nothing reaches the
 * outbox, so a service started on the database afterwards publishes none of it. Settings:
 * {@code load.mongo} (the URI), {@code load.database} ({@code console_state}), {@code load.size},
 * {@code load.accounts}, {@code load.seed}, {@code load.largestShare}, {@code load.concurrency}
 * (64) and {@code load.from} (the first index, to resume a cut-short run).
 */
public final class SeedStateEstate {

    private SeedStateEstate() {
    }

    public static void main(String[] args) {
        StateEstate estate = StateEstate.fromProperties();
        String uri = System.getProperty("load.mongo", "mongodb://localhost:27017");
        String database = System.getProperty("load.database", "console_state");
        int concurrency = Integer.getInteger("load.concurrency", 64);
        long from = Long.getLong("load.from", 0L);

        try (MongoClient client = MongoClients.create(uri)) {
            Mongo mongo = new Mongo(client.getDatabase(database));
            MongoResourceEventStore events = new MongoResourceEventStore(mongo);
            MongoResourceProjection projection = new MongoResourceProjection(mongo);
            MongoAccountKeys keys = new MongoAccountKeys(mongo);
            events.ensureIndexes().then(projection.ensureIndexes()).then(keys.ensureIndexes()).block();
            Clock clock = Clock.systemUTC();
            // the estate holds no sensitive values, so the master key never seals anything
            SensitiveData sensitive = new SensitiveData(keys, SensitiveData.randomMasterKey(), clock);
            InventoryService inventory = new InventoryService(new ResourceLoader(events, projection), events, projection, types(), sensitive,
                    new ResourceEventPublisher(noOutbox()), new InventoryMetrics(new SimpleMeterRegistry()), clock, "wf", Duration.ofDays(90));

            AtomicLong done = new AtomicLong();
            Instant started = Instant.now();
            System.out.printf("seeding %,d resources in %,d accounts into %s/%s from %,d%n", estate.size(), estate.accounts(), uri, database, from);
            Flux.range(0, (int) (estate.size() - from))
                    .map(n -> estate.item(from + n))
                    .flatMap(item -> seed(inventory, item).doOnSuccess(x -> {
                        long d = done.incrementAndGet();
                        if (d % 100_000 == 0) {
                            double perSecond = d / Math.max(1.0, Duration.between(started, Instant.now()).toSeconds());
                            System.out.printf("%,d of %,d (%.0f a second), up to index about %,d%n", d, estate.size() - from, perSecond, from + d);
                        }
                    }), concurrency)
                    .then()
                    .block();
            System.out.printf("seeded %,d resources in %s%n", done.get(), Duration.between(started, Instant.now()));
        }
    }

    private static Mono<Object> seed(InventoryService inventory, StateEstate.Item item) {
        AccountScope scope = AccountScope.of(item.accountId(), StateEstate.REGION, "load-seeder");
        String wfrn = ResourceIdentity.mint("wf", item.kind().service(), StateEstate.REGION, item.accountId(), item.kind().type()).wfrn();
        Commands.Meta meta = new Commands.Meta(null, "load-seeder", "seed-" + item.index());
        return inventory.importResource(scope, new Commands.Import(item.kind().type(), StateEstate.REGION, wfrn, item.registryId(),
                        "provider-" + item.registryId(), item.name(), item.attributes(), "backfill", meta))
                .flatMap(r -> inventory.tag(scope, r.wfrn(), new Commands.Tag(Map.of("env", item.env(), "team", item.team(), "cost-centre",
                        item.costCentre(), "cmdb", item.cmdb()), List.of(), meta)))
                .map(r -> (Object) r)
                // a run resumed over resources already there: the registry id is taken, carry on
                .onErrorResume(e -> e.getMessage() != null && e.getMessage().startsWith("registry.collision"), e -> Mono.empty());
    }

    private static ResourceTypesPort types() {
        List<ResourceTypeInfo> all = StateEstate.KINDS.stream()
                .map(k -> new ResourceTypeInfo(k.type(), null, k.service(), Map.of(), "missing", null, null, null))
                .toList();
        return new ResourceTypesPort() {
            @Override
            public Mono<ResourceTypeInfo> find(String name) {
                return Mono.justOrEmpty(all.stream().filter(t -> t.name().equals(name)).findFirst());
            }

            @Override
            public Flux<ResourceTypeInfo> list() {
                return Flux.fromIterable(all);
            }
        };
    }

    /**
     * An outbox that keeps nothing: the seeded estate is history, not news. A proxy, so it answers
     * whatever the service kit's port asks of it (an empty answer to all but an append).
     */
    static OutboxPort noOutbox() {
        AtomicLong seq = new AtomicLong();
        return (OutboxPort) Proxy.newProxyInstance(OutboxPort.class.getClassLoader(), new Class<?>[] {OutboxPort.class}, (proxy, method, args) -> {
            if (method.getName().equals("append")) {
                EventEnvelope event = (EventEnvelope) args[2];
                return Mono.just(new OutboxEntry(seq.incrementAndGet(), event.key(), (String) args[0], (String) args[1], event, Instant.now(),
                        Instant.now(), 0));
            }
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "no outbox";
                };
            }
            return method.getReturnType() == Flux.class ? Flux.empty() : Mono.empty();
        });
    }
}

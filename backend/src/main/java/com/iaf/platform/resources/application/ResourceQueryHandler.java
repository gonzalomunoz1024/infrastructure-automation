package com.iaf.platform.resources.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.PolicyInput;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceGraph;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.resources.ports.outbound.ResourceStorePort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read side of the registry. {@code use} is what the interpreter calls for a {@code use} step:
 * it asks the policy engine whether the requester may touch the resource. {@code awaitState}
 * waits for a resource to reach a state; the registry's {@code resource.*} events on the
 * {@link EventBus} wake it, nothing polls. Lineage is computed from {@code builtFrom} in both
 * directions.
 */
@Service
@Primary
public class ResourceQueryHandler implements ResourceQueries {

    /** How long {@code use} with {@code waitUntil} waits; a use step waits as long as its own timeout. */
    public static final Duration WAIT_AT_MOST = Duration.ofSeconds(60);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final ResourceStorePort store;
    private final PolicyPort policy;
    private final Duration waitAtMost;
    /** The open waits by resource id; each looks at the store again when an event about its resource arrives. */
    private final Map<String, Set<Runnable>> waiting = new ConcurrentHashMap<>();

    @Autowired
    public ResourceQueryHandler(ResourceStorePort store, PolicyPort policy, EventBus events) {
        this(store, policy, events, WAIT_AT_MOST);
    }

    public ResourceQueryHandler(ResourceStorePort store, PolicyPort policy, EventBus events, Duration waitAtMost) {
        this.store = store;
        this.policy = policy;
        this.waitAtMost = waitAtMost;
        events.register(this::changed);
    }

    @Override
    public Flux<ResourceTypeDefinition> types() {
        return Flux.defer(() -> Flux.fromIterable(store.types()));
    }

    @Override
    public Mono<ResourceTypeDefinition> type(String name) {
        return Mono.defer(() -> Mono.justOrEmpty(store.type(name)));
    }

    @Override
    public Flux<Resource> list(String type, String owner, String status) {
        return Flux.defer(() -> Flux.fromIterable(store.resources()))
                .filter(r -> type == null || type.isBlank() || type.equals(r.type()))
                .filter(r -> owner == null || owner.isBlank() || owner.equals(r.owner()))
                .filter(r -> status == null || status.isBlank() || status.equals(r.status()));
    }

    @Override
    public Mono<Resource> get(String id) {
        return Mono.defer(() -> Mono.justOrEmpty(store.resource(id)));
    }

    @Override
    public Mono<Resource> use(String type, String id, Identity requester, boolean checkAccess) {
        return use(type, id, requester, checkAccess, null);
    }

    @Override
    public Mono<Resource> use(String type, String id, Identity requester, boolean checkAccess, String waitUntil) {
        Identity who = requester == null ? Identity.SYSTEM : requester;
        Mono<Resource> found = Mono.defer(() -> Mono.justOrEmpty(find(type, id)))
                .switchIfEmpty(Mono.error(() -> new NotFoundException(type == null || type.isBlank() ? "resource" : type, id)));
        Mono<Resource> inState = waitUntil == null || waitUntil.isBlank() ? found
                : found.flatMap(r -> waitUntil.equals(r.status()) ? Mono.just(r)
                        : awaitState(type, id, waitUntil, waitAtMost).switchIfEmpty(Mono.defer(() -> notReady(type, id, waitUntil))));
        return checkAccess ? inState.flatMap(r -> checkAccess(r, who)) : inState;
    }

    @Override
    public Mono<Resource> awaitState(String type, String id, String state, Duration timeout) {
        return Mono.<Resource>create(sink -> {
            Runnable look = () -> find(type, id).filter(r -> state.equals(r.status())).ifPresent(sink::success);
            waiting.compute(id, (k, open) -> {
                Set<Runnable> s = open == null ? ConcurrentHashMap.newKeySet() : open;
                s.add(look);
                return s;
            });
            sink.onDispose(() -> waiting.computeIfPresent(id, (k, s) -> {
                s.remove(look);
                return s.isEmpty() ? null : s;
            }));
            // listening before this first look: a change between the look and the wait is not missed
            look.run();
        }).timeout(timeout, Mono.empty());
    }

    /** A registry event about a resource: the waits on it look again (on the publisher's thread, right after the save). */
    private void changed(DomainEvent e) {
        Set<Runnable> open = "resource".equals(e.aggregate()) && e.aggregateId() != null ? waiting.get(e.aggregateId()) : null;
        if (open != null) {
            open.forEach(Runnable::run);
        }
    }

    private Optional<Resource> find(String type, String id) {
        return store.resource(id).filter(r -> type == null || type.isBlank() || type.equals(r.type()));
    }

    private Mono<Resource> notReady(String type, String id, String waitUntil) {
        String status = find(type, id).map(Resource::status).orElse("unknown");
        return Mono.error(new ConflictException("resource not ready",
                Map.of("id", id, "status", status, "waitUntil", waitUntil, "waited", waitAtMost.toString())));
    }

    private Mono<Resource> checkAccess(Resource r, Identity who) {
        ObjectNode input = F.objectNode();
        input.put("id", r.id());
        input.put("type", r.type());
        input.put("status", r.status());
        input.put("owner", r.owner());
        input.put("visibility", r.visibility());
        input.set("attributes", r.attributes());
        ObjectNode context = F.objectNode();
        context.put("phase", "resource-access");
        return policy.evaluate(new PolicyInput(PolicyPort.Phase.RESOURCE_ACCESS, who, null, null, input, context))
                .defaultIfEmpty(PolicyDecision.allow(policy.engine()))
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        return Mono.just(r);
                    }
                    String why = decision.rules() == null || decision.rules().isEmpty() ? ""
                            : ": " + String.join("; ", decision.rules().stream().map(x -> x.message() == null ? x.id() : x.message()).toList());
                    return Mono.error(new ForbiddenException(who.name() + " may not use " + r.type() + " " + r.id() + why));
                });
    }

    /**
     * The direct neighbours as before, and the whole graph around the resource: what it was built
     * from and what was built from it, through any number of steps ({@link ResourceGraph} bounds
     * the walk and visits each resource once), the resource itself first among the nodes.
     */
    @Override
    public Mono<Lineage> lineage(String id) {
        return Mono.defer(() -> {
            Resource r = store.resource(id).orElseThrow(() -> new NotFoundException("resource", id));
            List<Resource> builtFrom = r.builtFrom() == null ? List.of()
                    : r.builtFrom().stream().map(store::resource).filter(Optional::isPresent).map(Optional::get).toList();
            List<Resource> dependents = store.builtFrom(id);
            ResourceGraph.Walk up = ResourceGraph.ancestors(r, store::resource);
            ResourceGraph.Walk down = ResourceGraph.descendants(r, store::builtFrom);
            Map<String, Resource> nodes = new LinkedHashMap<>();
            nodes.put(r.id(), r);
            up.nodes().forEach(n -> nodes.putIfAbsent(n.id(), n));
            down.nodes().forEach(n -> nodes.putIfAbsent(n.id(), n));
            List<ResourceGraph.Edge> edges = new ArrayList<>(up.edges());
            edges.addAll(down.edges());
            return Mono.just(new Lineage(r, builtFrom, dependents, List.copyOf(nodes.values()), List.copyOf(edges)));
        });
    }
}

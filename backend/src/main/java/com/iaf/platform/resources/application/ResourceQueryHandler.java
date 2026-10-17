package com.iaf.platform.resources.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.PolicyInput;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.resources.ports.outbound.ResourceStorePort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Read side of the registry. {@code use} is what the interpreter calls for a {@code use} step:
 * it can wait for a state (polling the store) and ask the policy engine whether the requester may
 * touch the resource. Lineage is computed from {@code builtFrom} in both directions.
 */
@Service
@Primary
public class ResourceQueryHandler implements ResourceQueries {

    public static final Duration POLL_EVERY = Duration.ofMillis(500);
    public static final Duration WAIT_AT_MOST = Duration.ofSeconds(60);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final ResourceStorePort store;
    private final PolicyPort policy;
    private final Duration pollEvery;
    private final Duration waitAtMost;

    @Autowired
    public ResourceQueryHandler(ResourceStorePort store, PolicyPort policy) {
        this(store, policy, POLL_EVERY, WAIT_AT_MOST);
    }

    public ResourceQueryHandler(ResourceStorePort store, PolicyPort policy, Duration pollEvery, Duration waitAtMost) {
        this.store = store;
        this.policy = policy;
        this.pollEvery = pollEvery;
        this.waitAtMost = waitAtMost;
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
                : found.flatMap(r -> waitUntil.equals(r.status()) ? Mono.just(r) : poll(type, id, waitUntil));
        return checkAccess ? inState.flatMap(r -> checkAccess(r, who)) : inState;
    }

    private Optional<Resource> find(String type, String id) {
        return store.resource(id).filter(r -> type == null || type.isBlank() || type.equals(r.type()));
    }

    /** Poll the store on a blocking-safe scheduler until the resource reaches the state, or give up after the wait window. */
    private Mono<Resource> poll(String type, String id, String waitUntil) {
        return Flux.interval(pollEvery, pollEvery, Schedulers.boundedElastic())
                .map(tick -> find(type, id))
                .filter(r -> r.isPresent() && waitUntil.equals(r.get().status()))
                .map(Optional::get)
                .next()
                .timeout(waitAtMost, Mono.defer(() -> {
                    String status = find(type, id).map(Resource::status).orElse("unknown");
                    return Mono.error(new ConflictException("resource not ready",
                            Map.of("id", id, "status", status, "waitUntil", waitUntil, "waited", waitAtMost.toString())));
                }));
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

    @Override
    public Mono<Lineage> lineage(String id) {
        return Mono.defer(() -> {
            Resource r = store.resource(id).orElseThrow(() -> new NotFoundException("resource", id));
            List<Resource> all = store.resources();
            List<Resource> builtFrom = r.builtFrom() == null ? List.of()
                    : r.builtFrom().stream().map(store::resource).filter(Optional::isPresent).map(Optional::get).toList();
            List<Resource> dependents = all.stream().filter(x -> x.builtFrom() != null && x.builtFrom().contains(id)).toList();
            return Mono.just(new Lineage(r, builtFrom, dependents));
        });
    }
}

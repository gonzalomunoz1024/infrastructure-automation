package com.iaf.platform.resources.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceGraph;
import com.iaf.platform.resources.domain.ResourceIds;
import com.iaf.platform.resources.domain.ResourceRules;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.inbound.ResourceCommands;
import com.iaf.platform.resources.ports.outbound.ResourceStorePort;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Write side of the registry. {@code register} comes from a running job (the interpreter's
 * register step): a new id is minted when none is given and the resource belongs to the
 * requester's first team (the type's owner when they have none) with the type's visibility; an
 * existing id is updated by its owner team, the job that created it or an admin
 * ({@link ResourceRules}), and the merged attributes must still match the type's schema.
 * {@code retire} is the owner team's or an admin's, and refuses while something is still built
 * from the resource, directly or through others ({@link ResourceGraph}).
 */
@Service
@Primary
@RequiredArgsConstructor
public class ResourceCommandHandler implements ResourceCommands {

    public static final String DEFAULT_STATE = "ready";
    public static final String RETIRED = "retired";
    public static final String VISIBILITY_TEAM = ResourceTypeDefinition.VISIBILITY_TEAM;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final ResourceStorePort store;
    private final EventBus events;
    private final Clock clock;

    @Override
    public Mono<Resource> register(RegisterResource command) {
        return Mono.fromCallable(() -> doRegister(command));
    }

    private Resource doRegister(RegisterResource c) {
        if (c == null || c.type() == null || c.type().isBlank()) {
            throw new IllegalArgumentException("register needs a resource type");
        }
        ResourceTypeDefinition type = store.type(c.type())
                .orElseThrow(() -> new IllegalArgumentException("Unknown resource type '" + c.type() + "'"));
        Identity requester = c.requester() == null ? Identity.SYSTEM : c.requester();
        Instant now = clock.instant();
        Resource existing = c.id() == null ? null : store.resource(c.id()).orElse(null);
        Resource saved;
        if (existing != null) {
            if (!existing.type().equals(type.name())) {
                throw new ConflictException("Resource " + c.id() + " is a " + existing.type() + ", not a " + type.name());
            }
            ResourceRules.requireUpdate(requester, existing, c.jobId(), "update");
            String state = c.state() == null || c.state().isBlank() ? existing.status() : c.state();
            requireState(type, state);
            ObjectNode attributes = existing.attributes() != null && existing.attributes().isObject() ? existing.attributes().deepCopy() : F.objectNode();
            if (c.attributes() != null && !c.attributes().isNull()) {
                if (!c.attributes().isObject()) {
                    throw new IllegalArgumentException("attributes is an object");
                }
                attributes.setAll((ObjectNode) c.attributes());
            }
            requireAttributes(type, existing.id(), attributes);
            List<String> builtFrom = c.builtFrom() == null || c.builtFrom().isEmpty() ? existing.builtFrom() : c.builtFrom();
            saved = new Resource(existing.id(), existing.type(), nameOf(attributes, existing.id()), state, attributes, existing.owner(),
                    existing.visibility(), existing.createdBy(), builtFrom == null ? List.of() : builtFrom, existing.createdAt(), now);
        } else {
            String id = c.id() == null || c.id().isBlank() ? ResourceIds.mint(type.name()) : c.id();
            String state = c.state() == null || c.state().isBlank() ? DEFAULT_STATE : c.state();
            requireState(type, state);
            JsonNode attributes = c.attributes() == null || c.attributes().isNull() ? F.objectNode() : c.attributes();
            if (!attributes.isObject()) {
                throw new IllegalArgumentException("attributes is an object");
            }
            requireAttributes(type, id, attributes);
            String owner = requester.teams() == null || requester.teams().isEmpty() ? type.owner() : requester.teams().get(0);
            saved = new Resource(id, type.name(), nameOf(attributes, id), state, attributes, owner, type.defaultVisibility(),
                    new Resource.Origin(c.jobId(), c.workflow(), c.version()),
                    c.builtFrom() == null ? List.of() : List.copyOf(c.builtFrom()), now, now);
        }
        store.save(saved);
        ObjectNode data = F.objectNode();
        data.put("id", saved.id());
        data.put("type", saved.type());
        data.put("state", saved.status());
        data.put("jobId", c.jobId());
        data.put("workflow", c.workflow());
        data.put("by", requester.id());
        data.put("created", existing == null);
        events.publish("resource.registered", "resource", saved.id(), requester.id(), data);
        return saved;
    }

    @Override
    public Mono<CommandAccepted> retire(String id) {
        return CurrentIdentity.current().map(who -> doRetire(id, who));
    }

    private CommandAccepted doRetire(String id, Identity who) {
        Resource r = store.resource(id).orElseThrow(() -> new NotFoundException("resource", id));
        ResourceRules.requireChange(who, r, "retire");
        List<Resource> dependents = ResourceGraph.descendants(r, store::builtFrom).nodes().stream()
                .filter(x -> !RETIRED.equals(x.status()))
                .toList();
        if (!dependents.isEmpty()) {
            throw new ConflictException("resource has dependents", dependents);
        }
        if (RETIRED.equals(r.status())) {
            return CommandAccepted.of(id, RETIRED);
        }
        store.save(r.withStatus(RETIRED, clock.instant()));
        ObjectNode data = F.objectNode();
        data.put("id", id);
        data.put("type", r.type());
        data.put("by", who.id());
        events.publish("resource.retired", "resource", id, who.id(), data);
        return CommandAccepted.of(id, RETIRED);
    }

    private static void requireState(ResourceTypeDefinition type, String state) {
        if (type.states() != null && !type.states().isEmpty() && !type.states().contains(state)) {
            throw new IllegalArgumentException("'" + state + "' is not a state of " + type.name() + " (one of " + type.states() + ")");
        }
    }

    /** The attributes the registry would keep, against the type's schema when it declares one. */
    private static void requireAttributes(ResourceTypeDefinition type, String id, JsonNode attributes) {
        List<Problem> problems = Schemas.validate(type.schema(), attributes);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("attributes of " + type.name() + " " + id + " do not match the type's schema: "
                    + String.join("; ", problems.stream().map(p -> (p.field() == null ? "" : p.field() + " ") + p.message()).toList()));
        }
    }

    private static String nameOf(JsonNode attributes, String id) {
        JsonNode n = attributes == null ? null : attributes.get("name");
        return n != null && n.isTextual() && !n.asText().isBlank() ? n.asText() : id;
    }
}

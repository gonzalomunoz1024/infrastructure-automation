package com.iaf.console.state.inventory.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.domain.ResourceIdentity;

import reactor.core.publisher.Mono;

/**
 * Puts a resource event on the outbox as the {@code state.resources} message of
 * {@code console-events.yaml}: the platform's envelope with {@code aggregate: resource}, the wfrn as
 * {@code aggregateId} and in the context, the key {@code <type>:<wfrn>:<version>}, the wfrn as the
 * partition key, and the event's fields in {@code data} with {@code version}, {@code state},
 * {@code resourceType} and {@code jobId} on every one. Sensitive values go out masked. The same
 * envelope is what {@code GET /v1/wfrn/{wfrn}/events} answers (the contract's {@code ResourceEvent}),
 * so a consumer of the topic and a reader of the log see one event under one {@code id}.
 */
public final class ResourceEventPublisher {

    /** The topic of every resource event. */
    public static final String TOPIC = "state.resources";

    private final OutboxPort outbox;

    public ResourceEventPublisher(OutboxPort outbox) {
        this.outbox = outbox;
    }

    public Mono<OutboxEntry> publish(ResourceEvent event, Resource after) {
        return Mono.deferContextual(ctx -> outbox.append(TOPIC, event.wfrn(),
                envelope(SensitiveData.mask(event), after == null ? null : after.type()).withTraceparent(Correlation.traceparent(ctx).orElse(null))));
    }

    /**
     * The envelope of an event, as it travels and as the log shows it. The event's data is copied as
     * given, so the caller decides whether its sensitive values are masked or revealed;
     * {@code resourceType} is the resource's when known, else the data's, else the wfrn's.
     */
    public static EventEnvelope envelope(ResourceEvent event, String resourceType) {
        ObjectNode data = event.data() == null ? Json.object() : event.data().deepCopy();
        data.put("version", event.version());
        data.put("state", event.state().wire());
        String type = resourceType != null ? resourceType : Json.text(data, "resourceType");
        data.put("resourceType", type != null ? type : ResourceIdentity.of(event.wfrn()).type());
        Json.putIf(data, "jobId", event.jobId());
        return EventEnvelope.of(event.id(), event.key(), event.type().wire(), event.at(), "resource", event.wfrn(), event.actor(), data)
                .withContext(new EventEnvelope.Context(event.accountId(), event.region(), event.wfrn(), event.requestId()), null);
    }
}

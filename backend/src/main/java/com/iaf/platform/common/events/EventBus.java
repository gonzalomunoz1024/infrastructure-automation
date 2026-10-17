package com.iaf.platform.common.events;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Ids;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * The in-process event bus. Publishing is synchronous for registered listeners (so a projection
 * is up to date when the command's {@code CommandAccepted} returns) and asynchronous for stream
 * subscribers (WebSocket and SSE clients).
 */
@Slf4j
@Component
public class EventBus {

    private final Clock clock;
    private final Sinks.Many<DomainEvent> sink = Sinks.many().multicast().onBackpressureBuffer(4096, false);
    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();

    public EventBus(Clock clock) {
        this.clock = clock;
    }

    public DomainEvent publish(String type, String aggregate, String aggregateId, String actor, JsonNode data) {
        DomainEvent event = new DomainEvent(Ids.next("evt"), clock.instant(), type, aggregate, aggregateId, actor, data);
        publish(event);
        return event;
    }

    public void publish(DomainEvent event) {
        for (EventListener l : listeners) {
            try {
                l.on(event);
            } catch (RuntimeException e) {
                log.warn("listener {} failed on {}: {}", l.getClass().getSimpleName(), event.type(), e.toString());
            }
        }
        sink.tryEmitNext(event);
    }

    /** Synchronous listeners: projections, the audit log. */
    public void register(EventListener listener) {
        listeners.add(listener);
    }

    /** Asynchronous stream of every event from now on. */
    public Flux<DomainEvent> stream() {
        return sink.asFlux();
    }

    @FunctionalInterface
    public interface EventListener {
        void on(DomainEvent event);
    }
}

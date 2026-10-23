package com.iaf.console.kit.outbox;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** The publisher without Kafka: keeps what was published and streams it to in-process subscribers. */
public final class InMemoryEventPublisher implements EventPublisherPort {

    private final List<OutboxEntry> published = new CopyOnWriteArrayList<>();
    private final Sinks.Many<OutboxEntry> sink = Sinks.many().multicast().onBackpressureBuffer(4096, false);

    @Override
    public Mono<Void> publish(OutboxEntry entry) {
        return Mono.fromRunnable(() -> {
            published.add(entry);
            sink.tryEmitNext(entry);
        });
    }

    /** Everything published so far, in order. */
    public List<OutboxEntry> published() {
        return List.copyOf(published);
    }

    /** Every entry published from now on. */
    public Flux<OutboxEntry> stream() {
        return sink.asFlux();
    }
}

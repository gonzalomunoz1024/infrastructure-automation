package com.iaf.console.drift.drift.adapters.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.drift.drift.application.StateEvents;
import com.iaf.console.kit.idempotency.IdempotentConsumer;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

/**
 * The {@code kafka} profile's reader of the state service's {@code state.resources} topic: each
 * event (the P-01 envelope) reaches {@link StateEvents} once, by its key, so a change to a
 * resource is seen at once instead of on the next sync. Offsets are acknowledged after each
 * record, so a crash replays at most what was in flight.
 */
public final class StateEventsConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StateEventsConsumer.class);

    private final String bootstrapServers;
    private final String groupId;
    private final List<String> topics;
    private final Map<String, String> extra;
    private final StateEvents events;
    private final IdempotentConsumer once;
    private final ObjectMapper json;
    private volatile Disposable subscription;

    public StateEventsConsumer(String bootstrapServers, String groupId, List<String> topics, Map<String, String> extra, StateEvents events,
            IdempotentConsumer once, ObjectMapper json) {
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
        this.topics = List.copyOf(topics);
        this.extra = Map.copyOf(extra);
        this.events = events;
        this.once = once;
        this.json = json;
    }

    @Override
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        Map<String, Object> props = new HashMap<>(extra);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        ReceiverOptions<String, String> options = ReceiverOptions.<String, String>create(props).subscription(topics);
        log.info("consuming {} from {} as group {}", topics, bootstrapServers, groupId);
        subscription = KafkaReceiver.create(options).receive()
                .concatMap(record -> apply(record).then(Mono.fromRunnable(() -> record.receiverOffset().acknowledge())))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("the Kafka consumer restarts after: {}", s.failure().toString())))
                .subscribe();
    }

    Mono<Void> apply(ReceiverRecord<String, String> record) {
        return Mono.defer(() -> handle(record.value()))
                .retryWhen(Retry.backoff(5, Duration.ofMillis(500)).maxBackoff(Duration.ofSeconds(5)))
                .onErrorResume(e -> {
                    log.error("record {}@{} of {} could not be applied after retries and is skipped: {}", record.partition(), record.offset(),
                            record.topic(), e.toString());
                    return Mono.empty();
                });
    }

    /** One envelope: its type, the wfrn in its context, and its key for the once-only check. */
    Mono<Void> handle(String value) {
        JsonNode envelope;
        try {
            envelope = json.readTree(value);
        } catch (java.io.IOException e) {
            return Mono.error(new IllegalArgumentException("a state event is not JSON", e));
        }
        String type = envelope.path("type").asText(null);
        String wfrn = envelope.path("context").path("wfrn").asText(null);
        if (wfrn == null) {
            wfrn = envelope.path("aggregateId").asText(null);
        }
        String key = envelope.path("key").asText(null);
        if (key == null) {
            key = envelope.path("id").asText(null);
        }
        if (type == null || wfrn == null || key == null || StateEvents.ECHOES.contains(type)) {
            return Mono.empty();
        }
        String target = wfrn;
        return once.process("drift:" + key, () -> events.handle(type, target)).then();
    }

    @Override
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }

    @Override
    public boolean isRunning() {
        return subscription != null;
    }
}

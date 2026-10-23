package com.iaf.console.search.index.adapters.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.search.index.application.EventHandler;
import com.iaf.console.search.index.application.EventSource;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

/**
 * The {@code kafka} profile's reader of {@code state.resources} and {@code drift.findings}: each
 * record is the P-01 envelope as JSON, handed to the event handler, and its offset acknowledged
 * once applied, so a crash replays at most what was in flight and the handler's idempotency on
 * the event key makes the replay harmless. A record that fails after a few retries is skipped
 * and counted, never blocks the partition.
 */
public final class KafkaEventConsumer implements EventSource {

    public static final String KIND = "kafka";

    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);

    private final String bootstrapServers;
    private final String groupId;
    private final List<String> topics;
    private final Map<String, String> extra;
    private final EventHandler handler;
    private final ObjectMapper json;
    private final AtomicLong handled = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicReference<Instant> lastEventAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private volatile Disposable subscription;

    public KafkaEventConsumer(String bootstrapServers, String groupId, List<String> topics, Map<String, String> extra, EventHandler handler,
            ObjectMapper json) {
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
        this.topics = List.copyOf(topics);
        this.extra = Map.copyOf(extra);
        this.handler = handler;
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
        props.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        ReceiverOptions<String, String> options = ReceiverOptions.<String, String>create(props).subscription(topics);
        log.info("consuming {} from {} as group {}", topics, bootstrapServers, groupId);
        subscription = KafkaReceiver.create(options).receive()
                .concatMap(record -> apply(record).then(Mono.fromRunnable(() -> record.receiverOffset().acknowledge())))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> {
                            lastError.set(String.valueOf(s.failure()));
                            log.warn("the Kafka consumer restarts after: {}", s.failure().toString());
                        }))
                .subscribe();
    }

    private Mono<Void> apply(ReceiverRecord<String, String> record) {
        return Mono.defer(() -> {
            EventEnvelope event;
            try {
                event = json.readValue(record.value(), EventEnvelope.class);
            } catch (JsonProcessingException e) {
                return Mono.error(new IllegalArgumentException("record " + record.partition() + "@" + record.offset() + " is not an envelope", e));
            }
            return handler.handle(event).doOnSuccess(fresh -> {
                handled.incrementAndGet();
                if (event.at() != null) {
                    lastEventAt.updateAndGet(prev -> prev == null || event.at().isAfter(prev) ? event.at() : prev);
                }
            }).then();
        })
                .retryWhen(Retry.backoff(5, Duration.ofMillis(500)).maxBackoff(Duration.ofSeconds(5)))
                .onErrorResume(e -> {
                    lastError.set(e.toString());
                    skipped.incrementAndGet();
                    log.error("record {}@{} of {} could not be applied after retries and is skipped: {}", record.partition(), record.offset(),
                            record.topic(), e.toString());
                    return Mono.empty();
                });
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

    @Override
    public SourceStatus status() {
        return new SourceStatus(KIND, isRunning(), lastEventAt.get(), lastError.get(), handled.get());
    }

    public long skipped() {
        return skipped.get();
    }
}

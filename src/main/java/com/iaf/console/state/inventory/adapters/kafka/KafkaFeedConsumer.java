package com.iaf.console.state.inventory.adapters.kafka;

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
import org.springframework.context.SmartLifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.state.inventory.application.FeedSource;
import com.iaf.console.state.inventory.application.PlatformEventHandler;
import com.iaf.console.state.inventory.application.SensitiveData;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

/**
 * The {@code kafka} profile's reader: the platform's {@code platform.jobs} and {@code platform.resources}
 * topics go to the event handler, and the console's {@code identity.changes} topic is watched for
 * an account's closure, which destroys the account's data key (the crypto-shred of WP 2.5). Offsets
 * are acknowledged after each record is applied, so a crash replays at most what was in flight,
 * and the handler's own idempotency (the event key) makes the replay harmless.
 */
public final class KafkaFeedConsumer implements SmartLifecycle, FeedSource {

    /** The console's identity topic. */
    public static final String IDENTITY_CHANGES = "identity.changes";

    private static final Logger log = LoggerFactory.getLogger(KafkaFeedConsumer.class);

    private final String bootstrapServers;
    private final String groupId;
    private final List<String> topics;
    private final Map<String, String> extra;
    private final PlatformEventHandler handler;
    private final SensitiveData sensitive;
    private final ObjectMapper json;
    private final AtomicLong lastSeq = new AtomicLong();
    private final AtomicLong handled = new AtomicLong();
    private final AtomicReference<Instant> lastEventAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private volatile Disposable subscription;

    public KafkaFeedConsumer(String bootstrapServers, String groupId, List<String> topics, Map<String, String> extra, PlatformEventHandler handler,
            SensitiveData sensitive, ObjectMapper json) {
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
        this.topics = List.copyOf(topics);
        this.extra = Map.copyOf(extra);
        this.handler = handler;
        this.sensitive = sensitive;
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
            try {
                if (IDENTITY_CHANGES.equals(record.topic())) {
                    return identityChange(json.readTree(record.value()));
                }
                FeedEvent event = json.readValue(record.value(), FeedEvent.class);
                return handler.handle(event).doOnSuccess(v -> {
                    lastSeq.set(event.seq());
                    lastEventAt.set(event.at());
                });
            } catch (JsonProcessingException e) {
                return Mono.error(new IllegalArgumentException("record " + record.partition() + "@" + record.offset() + " is not JSON", e));
            }
        })
                .retryWhen(Retry.backoff(5, Duration.ofMillis(500)).maxBackoff(Duration.ofSeconds(5)))
                .onErrorResume(e -> {
                    lastError.set(e.toString());
                    log.error("record {}@{} of {} could not be applied after retries and is skipped: {}", record.partition(), record.offset(),
                            record.topic(), e.toString());
                    return Mono.empty();
                })
                .doOnSuccess(v -> handled.incrementAndGet());
    }

    /** An account that closes loses its data key. */
    Mono<Void> identityChange(JsonNode envelope) {
        if (!"AccountChanged".equals(envelope.path("type").asText())) {
            return Mono.empty();
        }
        JsonNode data = envelope.path("data");
        boolean closed = "deleted".equals(data.path("action").asText()) || "closed".equals(data.path("snapshot").path("status").asText());
        if (!closed) {
            return Mono.empty();
        }
        String accountId = envelope.path("aggregateId").asText(null);
        if (accountId == null) {
            accountId = envelope.path("context").path("accountId").asText(null);
        }
        if (accountId == null) {
            return Mono.empty();
        }
        log.info("account {} closed: destroying its data key", accountId);
        return sensitive.shred(accountId).then();
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
    public FeedStatus status() {
        return new FeedStatus("kafka", isRunning(), lastSeq.get(), lastEventAt.get(), lastError.get(), handled.get());
    }
}

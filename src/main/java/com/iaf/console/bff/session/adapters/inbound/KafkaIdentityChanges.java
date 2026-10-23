package com.iaf.console.bff.session.adapters.inbound;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.session.application.MembershipChanges;
import com.iaf.console.bff.session.domain.MembershipChange;
import com.iaf.console.kit.events.EventEnvelope;

import reactor.core.Disposable;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.retry.Retry;

/**
 * The identity service's {@code identity.changes} for the sessions: each change that concerns a
 * person's or an account's memberships ({@link MembershipChange}) marks them, so the sessions it
 * concerns read their memberships again on their next request. Every instance has a consumer group
 * of its own and starts at the latest offset: a change heard before it started is covered by the
 * re-read interval.
 */
@Component
@Profile("kafka")
public class KafkaIdentityChanges implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaIdentityChanges.class);

    private final String bootstrapServers;
    private final IdentityChangesProperties properties;
    private final MembershipChanges changes;
    private final ObjectMapper json;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Disposable subscription;

    public KafkaIdentityChanges(@Value("${console.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            IdentityChangesProperties properties, MembershipChanges changes, ObjectMapper json) {
        this.bootstrapServers = bootstrapServers;
        this.properties = properties;
        this.changes = changes;
        this.json = json;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.groupPrefix() + "-" + UUID.randomUUID());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        ReceiverOptions<String, String> options = ReceiverOptions.<String, String>create(config).subscription(List.of(properties.topic()));
        subscription = KafkaReceiver.create(options).receive()
                .doOnNext(record -> {
                    try {
                        apply(json.readValue(record.value(), EventEnvelope.class));
                    } catch (Exception e) {
                        log.warn("skipping a message of {} that is not an event envelope: {}", record.topic(), e.getMessage());
                    }
                    record.receiverOffset().acknowledge();
                })
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("identity changes consumer restarting: {}", s.failure().getMessage())))
                .subscribe(r -> { }, error -> log.error("identity changes consumer stopped", error));
        log.info("sessions follow {} on {}", properties.topic(), bootstrapServers);
    }

    /** Marks whom the change concerns. */
    void apply(EventEnvelope envelope) {
        for (MembershipChange change : MembershipChange.of(envelope)) {
            changes.changed(change);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        Disposable s = subscription;
        if (s != null) {
            s.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}

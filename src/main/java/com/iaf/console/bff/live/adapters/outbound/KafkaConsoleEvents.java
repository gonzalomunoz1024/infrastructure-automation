package com.iaf.console.bff.live.adapters.outbound;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.live.application.ConsoleEventListener;
import com.iaf.console.bff.live.domain.ConsoleEvents;
import com.iaf.console.bff.live.domain.LiveHub;
import com.iaf.console.kit.events.EventEnvelope;

import reactor.core.Disposable;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.retry.Retry;

/**
 * The console's own events from Kafka into the live stream: every envelope of
 * {@code state.resources} and {@code drift.findings} reaches its account's pages, a threshold
 * crossed on {@code cost.events} as {@code budget.alert}, and every
 * {@code identity.changes} event drops the account's cached decisions; every envelope then goes to
 * the {@link ConsoleEventListener}s (the notifications, the weekly digest's identity record).
 * Offsets are acknowledged after the hub took the event; a message that is not an envelope is
 * logged and skipped.
 */
@Component
@Profile("kafka")
public class KafkaConsoleEvents implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsoleEvents.class);

    private final KafkaProperties properties;
    private final LiveHub hub;
    private final Access access;
    private final ObjectMapper json;
    private final ObjectProvider<ConsoleEventListener> listeners;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Disposable subscription;

    public KafkaConsoleEvents(KafkaProperties properties, LiveHub hub, Access access, ObjectMapper json, ObjectProvider<ConsoleEventListener> listeners) {
        this.properties = properties;
        this.hub = hub;
        this.access = access;
        this.json = json;
        this.listeners = listeners;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.groupId());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        ReceiverOptions<String, String> options = ReceiverOptions.<String, String>create(config).subscription(properties.topics());
        subscription = KafkaReceiver.create(options).receive()
                .doOnNext(record -> {
                    try {
                        EventEnvelope envelope = json.readValue(record.value(), EventEnvelope.class);
                        if (ConsoleEvents.changesIdentity(envelope) && envelope.accountId() != null) {
                            access.invalidate(envelope.accountId());
                        }
                        ConsoleEvents.toLive(envelope).ifPresent(mapped -> hub.publish(mapped.accountId(), mapped.event()));
                        listeners.orderedStream().forEach(listener -> {
                            try {
                                listener.onConsoleEvent(envelope);
                            } catch (RuntimeException e) {
                                log.warn("a console event listener failed on {} ({}): {}", envelope.key(), envelope.type(), e.toString());
                            }
                        });
                    } catch (Exception e) {
                        log.warn("skipping a message of {} that is not an event envelope: {}", record.topic(), e.getMessage());
                    }
                    record.receiverOffset().acknowledge();
                })
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("kafka consumer restarting: {}", s.failure().getMessage())))
                .subscribe(r -> { }, error -> log.error("kafka consumer stopped", error));
        log.info("following {} on {}", properties.topics(), properties.bootstrapServers());
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

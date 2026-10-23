package com.iaf.console.platformfake.app.feed;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.fake.PlatformFake;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.events.domain.FeedTopics;
import com.iaf.console.kit.events.EventEnvelope;

import reactor.core.Disposable;

/**
 * The Kafka half of the P-01 feed under the {@code kafka} profile: every event of the fake's feed,
 * the demo seed's included, goes unchanged to its channel's topic with the channel's partition key.
 * Consumers de-duplicate on the envelope's {@code key}, so a replay after a restart is harmless.
 */
@Component
@Profile("kafka")
public class KafkaFeedRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaFeedRelay.class);

    /** The topics and their partitions, as asyncapi-target.yaml binds them. */
    private static final Map<String, Integer> TOPICS = Map.of(FeedTopics.JOBS, 12, FeedTopics.RESOURCES, 12, FeedTopics.CONTENT, 3, FeedTopics.AUDIT, 3);

    private final PlatformFake fake;
    private final Producer<String, String> producer;
    private final String bootstrapServers;
    private final AtomicLong published = new AtomicLong();
    private volatile Disposable subscription;

    public KafkaFeedRelay(PlatformFake fake, @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        this(fake, producer(bootstrapServers), bootstrapServers);
    }

    /** With a given producer; tests pass a mock and no servers, so no topic is created. */
    public KafkaFeedRelay(PlatformFake fake, Producer<String, String> producer, String bootstrapServers) {
        this.fake = fake;
        this.producer = producer;
        this.bootstrapServers = bootstrapServers;
    }

    private static Producer<String, String> producer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "platform-fake");
        return new KafkaProducer<>(props, new StringSerializer(), new StringSerializer());
    }

    @Override
    public void start() {
        createTopics();
        subscription = fake.events().stream(0, List.of(), null).subscribe(this::send,
                e -> log.error("the Kafka relay stopped following the feed", e));
        log.info("relaying the event feed to Kafka at {}", bootstrapServers);
    }

    void send(EventEnvelope event) {
        Optional<FeedTopics.Target> target = FeedTopics.route(event);
        if (target.isEmpty()) {
            return;
        }
        producer.send(new ProducerRecord<>(target.get().topic(), target.get().key(), Json.write(event)), (metadata, error) -> {
            if (error != null) {
                log.warn("could not publish {} ({}) to {}: {}", event.key(), event.seq(), target.get().topic(), error.getMessage());
            } else {
                published.incrementAndGet();
            }
        });
    }

    /** Creates the feed's topics when the broker lacks them; a broker that refuses leaves them to auto-creation. */
    private void createTopics() {
        if (bootstrapServers == null) {
            return;
        }
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            Set<String> existing = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            List<NewTopic> missing = TOPICS.entrySet().stream().filter(t -> !existing.contains(t.getKey()))
                    .map(t -> new NewTopic(t.getKey(), t.getValue(), (short) 1)).toList();
            if (!missing.isEmpty()) {
                admin.createTopics(missing).all().get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("could not create the feed's topics ({}); relying on auto-creation", e.getMessage());
        }
    }

    /** How many events Kafka acknowledged. */
    public long published() {
        return published.get();
    }

    @Override
    public void stop() {
        Disposable current = subscription;
        if (current != null) {
            current.dispose();
        }
        producer.flush();
        producer.close();
    }

    @Override
    public boolean isRunning() {
        Disposable current = subscription;
        return current != null && !current.isDisposed();
    }
}

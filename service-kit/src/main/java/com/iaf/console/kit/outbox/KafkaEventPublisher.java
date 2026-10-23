package com.iaf.console.kit.outbox;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.SenderRecord;

/**
 * The publisher of the {@code kafka} profile: an idempotent producer ({@code acks=all}, at most five
 * requests in flight) that sends each envelope as JSON under its partition key, with the event's
 * type and key as record headers.
 */
public final class KafkaEventPublisher implements EventPublisherPort, AutoCloseable {

    private final KafkaSender<String, String> sender;
    private final ObjectMapper json;

    public KafkaEventPublisher(KafkaSender<String, String> sender, ObjectMapper json) {
        this.sender = sender;
        this.json = json;
    }

    /** A publisher on a new sender with the producer settings of the platform's relay. */
    public static KafkaEventPublisher create(String bootstrapServers, String clientId, Map<String, Object> extra, ObjectMapper json) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        if (extra != null) {
            props.putAll(extra);
        }
        return new KafkaEventPublisher(KafkaSender.create(SenderOptions.<String, String>create(props).maxInFlight(5)), json);
    }

    @Override
    public Mono<Void> publish(OutboxEntry entry) {
        return Mono.fromCallable(() -> record(entry))
                .flatMapMany(r -> sender.send(Mono.just(SenderRecord.create(r, entry.seq()))))
                .flatMap(result -> result.exception() == null ? Mono.<Void>empty() : Mono.<Void>error(result.exception()))
                .then();
    }

    private ProducerRecord<String, String> record(OutboxEntry entry) throws JsonProcessingException {
        ProducerRecord<String, String> record = new ProducerRecord<>(entry.topic(), entry.partitionKey(), json.writeValueAsString(entry.event()));
        record.headers().add("type", entry.event().type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("key", entry.key().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Override
    public void close() {
        sender.close();
    }
}

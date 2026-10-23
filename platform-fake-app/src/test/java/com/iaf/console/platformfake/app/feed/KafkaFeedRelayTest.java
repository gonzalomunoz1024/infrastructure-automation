package com.iaf.console.platformfake.app.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import com.iaf.console.fake.PlatformFake;
import com.iaf.console.fake.common.Json;
import com.iaf.console.kit.events.EventEnvelope;

class KafkaFeedRelayTest {

    @Test
    void everyFeedEventGoesUnchangedToItsTopic() throws Exception {
        try (PlatformFake fake = PlatformFake.builder().demo(false).generateDemoKey(false).build()) {
            EventEnvelope before = fake.events().emit("job.started", "job.started:job-1:1", null, "job", "job-1", "gonzalo",
                    Json.object().put("jobId", "job-1"), null, null, null);
            MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
            KafkaFeedRelay relay = new KafkaFeedRelay(fake, producer, null);
            relay.start();
            EventEnvelope after = fake.events().emit("resource.registered", "resource.registered:vm-1:1", null, "resource", "vm-1", "gonzalo",
                    Json.object().put("id", "vm-1"), null, null, null);
            List<ProducerRecord<String, String>> sent = awaitRecords(producer, 2);
            assertThat(sent).extracting(ProducerRecord::topic).containsExactly("platform.jobs", "platform.resources");
            assertThat(sent).extracting(ProducerRecord::key).containsExactly("job-1", "vm-1");
            assertThat(Json.read(sent.get(0).value()).path("key").asText()).isEqualTo(before.key());
            assertThat(Json.read(sent.get(1).value()).path("seq").asLong()).isEqualTo(after.seq());
            assertThat(relay.published()).isEqualTo(2);
            relay.stop();
            assertThat(relay.isRunning()).isFalse();
        }
    }

    private static List<ProducerRecord<String, String>> awaitRecords(MockProducer<String, String> producer, int count) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (producer.history().size() < count && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        return producer.history();
    }
}

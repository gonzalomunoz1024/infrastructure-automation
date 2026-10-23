package com.iaf.console.search.index.adapters.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.kit.idempotency.IdempotentConsumer;
import com.iaf.console.kit.idempotency.InMemoryProcessedEvents;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.TestClock;
import com.iaf.console.search.index.adapters.memory.InMemoryCheckpoints;
import com.iaf.console.search.index.adapters.memory.InMemorySearchIndex;
import com.iaf.console.search.index.application.EventHandler;
import com.iaf.console.search.index.application.IndexMetrics;
import com.iaf.console.search.index.application.IndexingService;
import com.iaf.console.search.index.domain.Fixtures;
import com.iaf.console.search.index.domain.IndexedResource;
import com.iaf.console.search.index.domain.Json;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.support.FakeStateService;
import com.iaf.console.search.support.FixedTypes;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** The consumer against the Kafka {@code CONSOLE_TEST_KAFKA_BOOTSTRAP} names (CI starts one); skipped without it. */
@EnabledIf("com.iaf.console.search.index.adapters.kafka.KafkaEventConsumerTest#available")
class KafkaEventConsumerTest {

    static boolean available() {
        String b = System.getenv("CONSOLE_TEST_KAFKA_BOOTSTRAP");
        return b != null && !b.isBlank();
    }

    private static IndexedResource awaitDocument(InMemorySearchIndex index, AccountScope scope, String wfrn, java.util.function.Predicate<IndexedResource> done)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        IndexedResource doc = null;
        while (System.nanoTime() < deadline) {
            doc = index.find(scope, wfrn).block();
            if (doc != null && done.test(doc)) {
                return doc;
            }
            Thread.sleep(200);
        }
        return doc;
    }

    @Test
    void eventsOnTheTopicsReachTheIndex() throws Exception {
        String bootstrap = System.getenv("CONSOLE_TEST_KAFKA_BOOTSTRAP").trim();
        TestClock clock = new TestClock(Fixtures.T0.plus(Duration.ofHours(1)));
        InMemorySearchIndex index = new InMemorySearchIndex(clock);
        IndexingService indexing = new IndexingService(index, new FakeStateService(), new FixedTypes(), new InMemoryCheckpoints(),
                new IndexMetrics(new SimpleMeterRegistry()), clock);
        EventHandler handler = new EventHandler(indexing, new IdempotentConsumer(new InMemoryProcessedEvents(), clock));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        // topics of this run alone, so records of an earlier run never reach this consumer
        String resources = "state.resources." + suffix;
        String findings = "drift.findings." + suffix;
        List<String> topics = List.of(resources, findings);
        try (Admin admin = Admin.create(Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            admin.createTopics(List.of(new NewTopic(resources, 1, (short) 1), new NewTopic(findings, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw e;
            }
        }
        KafkaEventConsumer consumer = new KafkaEventConsumer(bootstrap, "console-search-indexer-test-" + suffix, topics, Map.of(), handler, Json.MAPPER);
        consumer.start();
        try {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            String wfrn = "wfrn:wf:compute:global:123456789012:vm/vm-" + suffix;
            AccountScope scope = AccountScope.of("123456789012", "global", "test");
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                producer.send(new ProducerRecord<>(resources, wfrn, Json.MAPPER.writeValueAsString(Fixtures.requested(wfrn, "kafka-" + suffix, Map.of())))).get();
                producer.send(new ProducerRecord<>(resources, wfrn, "{not json")).get();
                producer.send(new ProducerRecord<>(resources, wfrn, Json.MAPPER.writeValueAsString(Fixtures.succeeded(wfrn, 2, Map.of("name", "kafka-" + suffix), Fixtures.T0.plusSeconds(2))))).get();
                // the two topics are read side by side, in no order between them: a finding is about a resource the index holds
                awaitDocument(index, scope, wfrn, d -> d.state() == ResourceState.ACTIVE);
                producer.send(new ProducerRecord<>(findings, wfrn, Json.MAPPER.writeValueAsString(Fixtures.finding("FindingOpened", wfrn, "f-" + suffix, 1, "critical", Fixtures.T0.plusSeconds(3))))).get();
            }
            IndexedResource doc = awaitDocument(index, scope, wfrn, IndexedResource::drift);
            assertThat(doc).isNotNull();
            assertThat(doc.state()).isEqualTo(ResourceState.ACTIVE);
            assertThat(doc.drift()).isTrue();
            assertThat(doc.driftSeverity()).isEqualTo("critical");
            assertThat(consumer.status().handled()).isGreaterThanOrEqualTo(3);
            assertThat(consumer.skipped()).as("the record that is not JSON is skipped, not blocking").isEqualTo(1);
            assertThat(consumer.status().lastEventAt()).isEqualTo(Fixtures.T0.plusSeconds(3));
        } finally {
            consumer.stop();
        }
    }
}

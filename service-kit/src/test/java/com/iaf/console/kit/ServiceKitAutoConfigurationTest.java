package com.iaf.console.kit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import com.iaf.console.kit.correlation.CorrelationWebFilter;
import com.iaf.console.kit.errors.ProblemExceptionHandler;
import com.iaf.console.kit.idempotency.IdempotentConsumer;
import com.iaf.console.kit.idempotency.InMemoryProcessedEvents;
import com.iaf.console.kit.idempotency.ProcessedEventsPort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.outbox.EventPublisherPort;
import com.iaf.console.kit.outbox.InMemoryEventPublisher;
import com.iaf.console.kit.outbox.InMemoryOutbox;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.kit.outbox.OutboxProperties;
import com.iaf.console.kit.outbox.OutboxRelay;

class ServiceKitAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, ServiceKitAutoConfiguration.class));

    @Test
    void wiresTheInMemoryTwinsWithoutProfiles() {
        runner.withPropertyValues("console.outbox.relay.enabled=false", "console.outbox.relay.batch-size=10").run(context -> {
            assertThat(context).hasSingleBean(ProblemExceptionHandler.class);
            assertThat(context).hasSingleBean(CorrelationWebFilter.class);
            assertThat(context).hasSingleBean(OutboxRelay.class);
            assertThat(context).hasSingleBean(IdempotentConsumer.class);
            assertThat(context.getBean(OutboxPort.class)).isInstanceOf(InMemoryOutbox.class);
            assertThat(context.getBean(EventPublisherPort.class)).isInstanceOf(InMemoryEventPublisher.class);
            assertThat(context.getBean(ProcessedEventsPort.class)).isInstanceOf(InMemoryProcessedEvents.class);
            assertThat(context.getBean(OutboxProperties.class).relay().batchSize()).isEqualTo(10);
            assertThat(context.getBean(OutboxProperties.class).relay().enabled()).isFalse();
            assertThat(context.getBean(OutboxRelay.class).running()).isFalse();
            assertThat(context).doesNotHaveBean(Mongo.class);
        });
    }

    @Test
    void theRelayRunsWhenEnabled() {
        runner.withPropertyValues("console.outbox.relay.interval=50ms").run(context -> {
            assertThat(context.getBean(OutboxRelay.class).running()).isTrue();
        });
    }
}

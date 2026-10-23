package com.iaf.console.kit.idempotency;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.iaf.console.kit.mongo.Mongo;

/** The processed-events store by profile, and the consumer built on it. */
@Configuration(proxyBeanMethods = false)
public class IdempotencyConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public ProcessedEventsPort mongoProcessedEvents(Mongo mongo) {
        return new MongoProcessedEvents(mongo);
    }

    @Bean
    @Profile("!mongo")
    public ProcessedEventsPort inMemoryProcessedEvents() {
        return new InMemoryProcessedEvents();
    }

    @Bean
    public IdempotentConsumer idempotentConsumer(ProcessedEventsPort processed) {
        return new IdempotentConsumer(processed, Clock.systemUTC());
    }
}

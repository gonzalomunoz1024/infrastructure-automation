package com.iaf.console.identity.common.idempotency;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.kit.mongo.Mongo;

/** The idempotency store by profile and the filter that uses it. */
@Configuration(proxyBeanMethods = false)
public class IdempotencyConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public IdempotencyPort mongoIdempotency(Mongo mongo) {
        return new MongoIdempotency(mongo);
    }

    @Bean
    @Profile("!mongo")
    public IdempotencyPort inMemoryIdempotency() {
        return new InMemoryIdempotency();
    }

    @Bean
    public IdempotencyWebFilter idempotencyWebFilter(IdempotencyPort store, Clock clock, ObjectMapper json) {
        return new IdempotencyWebFilter(store, clock, json);
    }
}

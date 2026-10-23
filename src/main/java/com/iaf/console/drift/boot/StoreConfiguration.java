package com.iaf.console.drift.boot;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.drift.drift.adapters.memory.InMemoryDiscoveryStore;
import com.iaf.console.drift.drift.adapters.memory.InMemoryFindingStore;
import com.iaf.console.drift.drift.adapters.memory.InMemoryIdempotencyKeys;
import com.iaf.console.drift.drift.adapters.memory.InMemoryWatchStore;
import com.iaf.console.drift.drift.adapters.mongo.MongoDiscoveryStore;
import com.iaf.console.drift.drift.adapters.mongo.MongoFindingStore;
import com.iaf.console.drift.drift.adapters.mongo.MongoIdempotencyKeys;
import com.iaf.console.drift.drift.adapters.mongo.MongoWatchStore;
import com.iaf.console.kit.mongo.Mongo;

/** Where findings, watches, idempotency keys and discovery live: MongoDB under the {@code mongo} profile, memory otherwise. */
@Configuration(proxyBeanMethods = false)
public class StoreConfiguration {

    @Configuration(proxyBeanMethods = false)
    @Profile("mongo")
    static class MongoStores {

        @Bean
        public MongoFindingStore findingStore(Mongo mongo) {
            return new MongoFindingStore(mongo);
        }

        @Bean
        public MongoWatchStore watchStore(Mongo mongo) {
            return new MongoWatchStore(mongo);
        }

        @Bean
        public MongoIdempotencyKeys idempotencyKeys(Mongo mongo) {
            return new MongoIdempotencyKeys(mongo);
        }

        @Bean
        public MongoDiscoveryStore discoveryStore(Mongo mongo) {
            return new MongoDiscoveryStore(mongo);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("!mongo")
    static class MemoryStores {

        @Bean
        public InMemoryFindingStore findingStore() {
            return new InMemoryFindingStore();
        }

        @Bean
        public InMemoryWatchStore watchStore() {
            return new InMemoryWatchStore();
        }

        @Bean
        public InMemoryIdempotencyKeys idempotencyKeys(Clock clock) {
            return new InMemoryIdempotencyKeys(clock);
        }

        @Bean
        public InMemoryDiscoveryStore discoveryStore() {
            return new InMemoryDiscoveryStore();
        }
    }
}

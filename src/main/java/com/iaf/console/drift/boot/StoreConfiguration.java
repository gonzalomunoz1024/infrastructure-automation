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
import com.iaf.console.drift.monitoring.adapters.memory.InMemoryAlarmStore;
import com.iaf.console.drift.monitoring.adapters.mongo.MongoAlarmStore;
import com.iaf.console.drift.rightsizing.adapters.memory.InMemoryRecommendationStore;
import com.iaf.console.drift.rightsizing.adapters.memory.InMemoryUtilisationStore;
import com.iaf.console.drift.rightsizing.adapters.mongo.MongoRecommendationStore;
import com.iaf.console.drift.rightsizing.adapters.mongo.MongoUtilisationStore;
import com.iaf.console.kit.mongo.Mongo;

/**
 * Where findings, watches, idempotency keys, discovery, alarms, recommendations and utilisation live:
 * MongoDB under the {@code mongo} profile, memory otherwise.
 */
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

        @Bean
        public MongoAlarmStore alarmStore(Mongo mongo) {
            return new MongoAlarmStore(mongo);
        }

        @Bean
        public MongoRecommendationStore recommendationStore(Mongo mongo) {
            return new MongoRecommendationStore(mongo);
        }

        @Bean
        public MongoUtilisationStore utilisationStore(Mongo mongo) {
            return new MongoUtilisationStore(mongo);
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

        @Bean
        public InMemoryAlarmStore alarmStore() {
            return new InMemoryAlarmStore();
        }

        @Bean
        public InMemoryRecommendationStore recommendationStore() {
            return new InMemoryRecommendationStore();
        }

        @Bean
        public InMemoryUtilisationStore utilisationStore() {
            return new InMemoryUtilisationStore();
        }
    }
}

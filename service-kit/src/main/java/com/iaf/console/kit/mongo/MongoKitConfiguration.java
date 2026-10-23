package com.iaf.console.kit.mongo;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.mongodb.reactivestreams.client.MongoClient;

/**
 * The {@code mongo} profile's shared database handle, from Spring Boot's reactive client and
 * {@code spring.data.mongodb.database}, and the start-up hook that creates every adapter's indexes.
 */
@Configuration(proxyBeanMethods = false)
@Profile("mongo")
public class MongoKitConfiguration {

    @Bean
    public Mongo mongo(MongoClient client, MongoProperties properties) {
        return new Mongo(client.getDatabase(properties.getMongoClientDatabase()));
    }

    @Bean
    public SmartLifecycle mongoIndexesAtStart(ObjectProvider<MongoIndexed> adapters) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                List<MongoIndexed> all = adapters.orderedStream().toList();
                MongoIndexes.ensureAllNow(all, Duration.ofSeconds(60));
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                // before the relay and the web server, which are at the default phase
                return Integer.MIN_VALUE + 1000;
            }
        };
    }
}

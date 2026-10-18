package com.iaf.platform.common.adapters.mongo;

import java.time.Clock;

import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.platform.config.PlatformProperties;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import lombok.extern.slf4j.Slf4j;

/**
 * The {@code mongo} profile: one {@link MongoClient} on {@code spring.data.mongodb.uri} (so
 * {@code SPRING_DATA_MONGODB_URI} from the environment, credentials included; {@value #DEFAULT_URI}
 * when unset), the database it names (or {@code spring.data.mongodb.database}, else
 * {@value #DEFAULT_DATABASE}), and the
 * {@link Mongo} every MongoDB adapter uses. Each adapter creates the indexes it relies on when it
 * starts. Without the profile there is no client at all ({@code PlatformApplication} leaves the
 * MongoDB auto-configuration out), and the in-memory adapters serve every port.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@Profile("mongo")
@EnableConfigurationProperties(MongoProperties.class)
public class MongoConfig {

    /** The seed name of the demo history in {@code store_seeds}. */
    public static final String DEMO_HISTORY = "demo-history";
    static final String DEFAULT_DATABASE = "platform";
    static final String DEFAULT_URI = "mongodb://localhost:27017/" + DEFAULT_DATABASE;

    @Bean(destroyMethod = "close")
    public MongoClient mongoClient(MongoProperties properties) {
        return MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri(properties)))
                .applicationName("infrastructure-automation")
                .build());
    }

    @Bean
    public Mongo mongo(MongoClient client, MongoProperties properties, PlatformProperties props, Clock clock) {
        Mongo mongo = new Mongo(client.getDatabase(database(properties)));
        demoHistoryOnce(mongo, props, clock);
        return mongo;
    }

    static String uri(MongoProperties properties) {
        return properties.getUri() == null || properties.getUri().isBlank() ? DEFAULT_URI : properties.getUri();
    }

    /** {@code spring.data.mongodb.database}, else the database the URI names, else {@value #DEFAULT_DATABASE}. */
    static String database(MongoProperties properties) {
        if (properties.getDatabase() != null && !properties.getDatabase().isBlank()) {
            return properties.getDatabase();
        }
        String named = new ConnectionString(uri(properties)).getDatabase();
        return named == null || named.isBlank() ? DEFAULT_DATABASE : named;
    }

    /**
     * The demo history ({@code platform.demo.seed-history}: two weeks of jobs, provider calls,
     * releases and resources) is written once per database. The first start claims it; a later
     * one finds it claimed and turns the switch off before any seeder runs (the stores a seeder
     * writes to are built on this bean, and the job and telemetry seeders wait for the application
     * to be ready), so a restart does not stack another history on top.
     */
    static void demoHistoryOnce(Mongo mongo, PlatformProperties props, Clock clock) {
        if (props.getDemo() == null || !props.getDemo().isSeedHistory()) {
            return;
        }
        if (mongo.claimSeed(DEMO_HISTORY, clock.instant())) {
            log.info("demo history: seeding this database (once)");
            return;
        }
        props.getDemo().setSeedHistory(false);
        log.info("demo history: the database has it since {}; not seeded again", mongo.seededAt(DEMO_HISTORY).map(Object::toString).orElse("an earlier start"));
    }
}

package com.iaf.platform.common.adapters.mongo;

import org.bson.Document;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.mongodb.client.MongoClient;

/**
 * {@code /actuator/health}'s {@code mongo} component: up when the database answers a ping. Not
 * named {@code mongo}, which is the {@link Mongo} bean's name.
 */
@Component
@Profile("mongo")
public class MongoHealthIndicator extends AbstractHealthIndicator {

    private final MongoClient client;
    private final Mongo mongo;

    public MongoHealthIndicator(MongoClient client, Mongo mongo) {
        super("MongoDB health check failed");
        this.client = client;
        this.mongo = mongo;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Document hello = client.getDatabase(mongo.name()).runCommand(new Document("hello", 1));
        builder.up().withDetail("database", mongo.name());
        if (hello.get("setName") != null) {
            builder.withDetail("replicaSet", hello.get("setName"));
        }
    }
}

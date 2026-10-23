package com.iaf.console.state.inventory.adapters.mongo;

import java.util.UUID;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.iaf.console.kit.mongo.Mongo;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

import reactor.core.publisher.Mono;

/**
 * Where the MongoDB adapter tests run: the database {@code CONSOLE_TEST_MONGO_URI} names, else a
 * Testcontainers MongoDB when Docker is there, else nowhere (the tests are skipped). CI sets the
 * variable; the service kit's own adapter tests follow the same rule.
 */
public final class MongoTestSupport {

    /** The environment variable that names a running MongoDB. */
    public static final String URI_VARIABLE = "CONSOLE_TEST_MONGO_URI";

    private static boolean resolved;
    private static String uri;
    private static MongoDBContainer container;

    private MongoTestSupport() {
    }

    /** What {@code @EnabledIf} asks. */
    public static synchronized boolean available() {
        return uri() != null;
    }

    public static synchronized String uri() {
        if (resolved) {
            return uri;
        }
        resolved = true;
        String fromEnvironment = System.getenv(URI_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            uri = fromEnvironment.trim();
            return uri;
        }
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                container = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
                container.start();
                uri = container.getConnectionString();
            }
        } catch (RuntimeException e) {
            uri = null;
        }
        return uri;
    }

    /** A client on the test database. */
    public static MongoClient client() {
        return MongoClients.create(uri());
    }

    /** A fresh database name per test class, so runs never see each other's documents. */
    public static String freshDatabase(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static void drop(Mongo mongo) {
        Mono.from(mongo.database().drop()).block();
    }
}

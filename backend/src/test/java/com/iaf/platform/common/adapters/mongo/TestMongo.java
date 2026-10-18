package com.iaf.platform.common.adapters.mongo;

import java.util.UUID;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * A fresh database for each test of a MongoDB adapter, built without Spring, in the MongoDB named
 * by {@value #MONGO_URI} (for example {@code mongodb://localhost:27017}) and dropped after the
 * test. Register it with {@code @RegisterExtension} on a class marked
 * {@code @EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")}; it is ready
 * before the test's own {@code @BeforeEach}. One client serves every test of the run.
 */
public final class TestMongo implements BeforeEachCallback, AfterEachCallback {

    /** The environment variable naming a MongoDB for the tests; unset, the MongoDB tests are skipped. */
    public static final String MONGO_URI = "PLATFORM_TEST_MONGO_URI";

    private static MongoClient client;

    private MongoDatabase database;
    private Mongo mongo;

    /** The shared client of the run, on {@value #MONGO_URI}. */
    public static synchronized MongoClient client() {
        if (client == null) {
            client = MongoClients.create(uri());
        }
        return client;
    }

    /** {@value #MONGO_URI}, or an error when it is not set. */
    public static String uri() {
        String uri = System.getenv(MONGO_URI);
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException(MONGO_URI + " is not set");
        }
        return uri;
    }

    /** {@value #MONGO_URI} with {@code database} as the database it names (for a Spring context's spring.data.mongodb.uri). */
    public static String uriOf(String database) {
        ConnectionString cs = new ConnectionString(uri());
        String hosts = String.join(",", cs.getHosts());
        String scheme = uri().startsWith("mongodb+srv://") ? "mongodb+srv://" : "mongodb://";
        String credentials = cs.getUsername() == null ? "" : cs.getUsername() + ":" + new String(cs.getPassword()) + "@";
        String query = uri().contains("?") ? uri().substring(uri().indexOf('?')) : "";
        return scheme + credentials + hosts + "/" + database + query;
    }

    /** A new database name no other test uses. */
    public static String freshName() {
        return "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        database = client().getDatabase(freshName());
        mongo = new Mongo(database);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (database != null) {
            database.drop();
            database = null;
        }
    }

    public Mongo mongo() {
        return mongo;
    }

    public MongoDatabase database() {
        return database;
    }
}

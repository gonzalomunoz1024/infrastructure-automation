package com.iaf.console.state.trail.adapters.mongo;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.state.inventory.adapters.mongo.MongoTestSupport;
import com.iaf.console.state.trail.adapters.TrailDigestStoreContract;
import com.iaf.console.state.trail.ports.outbound.TrailDigestPort;
import com.mongodb.reactivestreams.client.MongoClient;

/** The trail store's contract on MongoDB, with its index. */
@EnabledIf("com.iaf.console.state.inventory.adapters.mongo.MongoTestSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoTrailDigestsTest extends TrailDigestStoreContract {

    private MongoClient client;
    private Mongo mongo;
    private MongoTrailDigests digests;

    @BeforeAll
    void connect() {
        client = MongoTestSupport.client();
        mongo = new Mongo(client.getDatabase(MongoTestSupport.freshDatabase("console_state_trail")));
        digests = new MongoTrailDigests(mongo);
        digests.ensureIndexes().block();
    }

    @AfterAll
    void disconnect() {
        if (mongo != null) {
            MongoTestSupport.drop(mongo);
        }
        if (client != null) {
            client.close();
        }
    }

    @Override
    protected TrailDigestPort digests() {
        return digests;
    }
}

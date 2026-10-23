package com.iaf.console.drift.monitoring.adapters.mongo;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.drift.drift.adapters.mongo.MongoTestSupport;
import com.iaf.console.drift.monitoring.adapters.AlarmStoreContract;
import com.iaf.console.drift.monitoring.ports.outbound.AlarmStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.mongodb.reactivestreams.client.MongoClient;

import reactor.core.publisher.Mono;

/** The alarm store against a real MongoDB: the same rules as in memory, with its indexes in place. */
@EnabledIf("com.iaf.console.drift.drift.adapters.mongo.MongoTestSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoAlarmStoreTest extends AlarmStoreContract {

    private MongoClient client;
    private Mongo mongo;
    private MongoAlarmStore store;

    @BeforeAll
    void connect() {
        client = MongoTestSupport.client();
        mongo = new Mongo(client.getDatabase(MongoTestSupport.freshDatabase("console_drift_alarms_test")));
        store = new MongoAlarmStore(mongo);
        store.ensureIndexes().block();
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
    protected AlarmStorePort fresh() {
        Mono.from(mongo.collection(MongoAlarmStore.COLLECTION).deleteMany(new Document())).block();
        return store;
    }
}

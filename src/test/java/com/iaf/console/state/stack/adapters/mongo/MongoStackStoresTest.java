package com.iaf.console.state.stack.adapters.mongo;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.state.inventory.adapters.mongo.MongoTestSupport;
import com.iaf.console.state.stack.adapters.StackStoreContract;
import com.iaf.console.state.stack.ports.outbound.StackEventStorePort;
import com.iaf.console.state.stack.ports.outbound.StackStorePort;
import com.mongodb.reactivestreams.client.MongoClient;

/** The stack stores' contract on MongoDB, with their indexes (the live name's partial unique one among them). */
@EnabledIf("com.iaf.console.state.inventory.adapters.mongo.MongoTestSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoStackStoresTest extends StackStoreContract {

    private MongoClient client;
    private Mongo mongo;
    private MongoStacks stacks;
    private MongoStackEvents events;

    @BeforeAll
    void connect() {
        client = MongoTestSupport.client();
        mongo = new Mongo(client.getDatabase(MongoTestSupport.freshDatabase("console_state_stacks")));
        stacks = new MongoStacks(mongo);
        events = new MongoStackEvents(mongo);
        stacks.ensureIndexes().block();
        events.ensureIndexes().block();
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
    protected StackStorePort stacks() {
        return stacks;
    }

    @Override
    protected StackEventStorePort events() {
        return events;
    }
}

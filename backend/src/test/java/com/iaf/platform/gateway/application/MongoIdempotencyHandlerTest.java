package com.iaf.platform.gateway.application;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.gateway.adapters.outbound.MongoIdempotencyStore;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;

/** The handler over the MongoDB store: the same contract as in memory. */
@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoIdempotencyHandlerTest extends IdempotencyHandlerContract {

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Override
    IdempotencyStore newStore() {
        return new MongoIdempotencyStore(db.mongo());
    }
}

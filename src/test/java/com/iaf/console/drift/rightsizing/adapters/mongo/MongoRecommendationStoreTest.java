package com.iaf.console.drift.rightsizing.adapters.mongo;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.drift.drift.adapters.mongo.MongoTestSupport;
import com.iaf.console.drift.rightsizing.adapters.RecommendationStoreContract;
import com.iaf.console.drift.rightsizing.ports.outbound.RecommendationStorePort;
import com.iaf.console.drift.rightsizing.ports.outbound.UtilisationStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.mongodb.reactivestreams.client.MongoClient;

import reactor.core.publisher.Mono;

/**
 * The recommendation and utilisation stores against a real MongoDB: the same rules as in memory,
 * the partial unique index keeping one current recommendation per resource, and money that comes
 * back as it went in.
 */
@EnabledIf("com.iaf.console.drift.drift.adapters.mongo.MongoTestSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoRecommendationStoreTest extends RecommendationStoreContract {

    private MongoClient client;
    private Mongo mongo;
    private MongoRecommendationStore recommendations;
    private MongoUtilisationStore utilisation;

    @BeforeAll
    void connect() {
        client = MongoTestSupport.client();
        mongo = new Mongo(client.getDatabase(MongoTestSupport.freshDatabase("console_drift_rightsizing_test")));
        recommendations = new MongoRecommendationStore(mongo);
        utilisation = new MongoUtilisationStore(mongo);
        recommendations.ensureIndexes().block();
        utilisation.ensureIndexes().block();
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
    protected RecommendationStorePort freshRecommendations() {
        Mono.from(mongo.collection(MongoRecommendationStore.COLLECTION).deleteMany(new Document())).block();
        return recommendations;
    }

    @Override
    protected UtilisationStorePort freshUtilisation() {
        Mono.from(mongo.collection(MongoUtilisationStore.COLLECTION).deleteMany(new Document())).block();
        return utilisation;
    }
}

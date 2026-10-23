package com.iaf.console.identity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

import reactor.core.publisher.Mono;

/** The same access review flows on MongoDB (profile {@code mongo}), in a database of their own, when {@code CONSOLE_TEST_MONGO_URI} names one. */
@ActiveProfiles("mongo")
@EnabledIf("com.iaf.console.identity.MongoSupport#available")
class AccessReviewsMongoTest extends AccessReviewFlows {

    static final String DATABASE = MongoSupport.freshDatabase("console_identity_reviews");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MongoSupport::uri);
        registry.add("spring.data.mongodb.database", () -> DATABASE);
    }

    @AfterAll
    static void dropDatabase() {
        try (MongoClient client = MongoClients.create(MongoSupport.uri())) {
            Mono.from(client.getDatabase(DATABASE).drop()).block();
        }
    }
}

package com.iaf.console.bff.preferences.adapters.outbound;

import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.preferences.domain.Preferences;
import com.iaf.console.bff.preferences.ports.outbound.PreferencesStore;
import com.iaf.console.kit.mongo.Mongo;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;

import reactor.core.publisher.Mono;

/** Preferences in the {@code preferences} collection, {@code _id} the user id. */
@Component
@Profile("mongo")
public class MongoPreferencesStore implements PreferencesStore {

    static final String COLLECTION = "preferences";

    private final Mongo mongo;

    public MongoPreferencesStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Preferences> find(String userId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", userId)).first()).map(d -> mongo.read(d, Preferences.class));
    }

    @Override
    public Mono<Void> save(String userId, Preferences preferences) {
        Document d = new Document("_id", userId).append(Mongo.DOC, mongo.doc(preferences));
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", userId), d, new ReplaceOptions().upsert(true))).then();
    }
}

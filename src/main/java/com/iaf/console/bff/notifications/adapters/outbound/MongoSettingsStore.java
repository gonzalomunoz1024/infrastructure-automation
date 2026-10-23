package com.iaf.console.bff.notifications.adapters.outbound;

import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.notifications.domain.NotificationSettings;
import com.iaf.console.bff.notifications.ports.outbound.SettingsStore;
import com.iaf.console.kit.mongo.Mongo;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;

import reactor.core.publisher.Mono;

/**
 * Notification settings in the {@code notification_settings} collection, {@code _id} the user id
 * and {@code version} beside the record: the first save inserts, every later one replaces only
 * the version it was made against, so a replace that lost a race saves nothing.
 */
@Component
@Profile("mongo")
public class MongoSettingsStore implements SettingsStore {

    static final String COLLECTION = "notification_settings";

    private final Mongo mongo;

    public MongoSettingsStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<NotificationSettings> find(String userId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", userId)).first()).map(d -> mongo.read(d, NotificationSettings.class));
    }

    @Override
    public Mono<Boolean> replace(String userId, NotificationSettings settings, long expectedVersion) {
        Document d = new Document("_id", userId).append("version", settings.version()).append(Mongo.DOC, mongo.doc(settings));
        if (expectedVersion == 0) {
            return Mono.from(mongo.collection(COLLECTION).insertOne(d)).thenReturn(true)
                    .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.just(false) : Mono.error(e));
        }
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.and(Filters.eq("_id", userId), Filters.eq("version", expectedVersion)), d))
                .map(result -> result.getMatchedCount() == 1);
    }
}

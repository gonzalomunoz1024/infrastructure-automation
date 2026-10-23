package com.iaf.console.bff.notifications.adapters.outbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.notifications.application.NotificationsProperties;
import com.iaf.console.bff.notifications.domain.FeedQuery;
import com.iaf.console.bff.notifications.domain.Notification;
import com.iaf.console.bff.notifications.ports.outbound.NotificationFeed;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The feeds in the {@code notifications} collection, shared by every replica: {@code _id} the
 * notification's id (from the event's key and the person, so a second insert of one event for one
 * person is a duplicate key and the first one wins), {@code userId}, {@code accountId},
 * {@code kind}, {@code read} and {@code createdAt} (epoch nanoseconds) beside the record for the
 * queries, and a TTL index on {@code expiresAt} that lets a notification go after the retention.
 */
@Component
@Profile("mongo")
public class MongoNotificationFeed implements NotificationFeed, MongoIndexed {

    static final String COLLECTION = "notifications";

    private final Mongo mongo;
    private final NotificationsProperties properties;

    public MongoNotificationFeed(Mongo mongo, NotificationsProperties properties) {
        this.mongo = mongo;
        this.properties = properties;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("userId"), Indexes.descending("createdAt"), Indexes.descending("_id"))),
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("userId"), Indexes.ascending("read"), Indexes.ascending("accountId"))),
                new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<Boolean> insert(String userId, Notification notification) {
        Document d = new Document("_id", notification.id())
                .append("userId", userId)
                .append("accountId", notification.accountId())
                .append("kind", notification.kind())
                .append("read", notification.readAt() != null)
                .append("createdAt", FeedQuery.sortValue(notification))
                .append("expiresAt", Mongo.date(notification.createdAt().plus(properties.retention())))
                .append(Mongo.DOC, mongo.doc(notification));
        return Mono.from(mongo.collection(COLLECTION).insertOne(d)).thenReturn(true)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.just(false) : Mono.error(e));
    }

    @Override
    public Mono<List<Notification>> list(String userId, FeedQuery query) {
        List<Bson> filters = new ArrayList<>(filters(userId, query));
        if (query.after() != null) {
            long at = query.after().sortLong();
            filters.add(Filters.or(Filters.lt("createdAt", at), Filters.and(Filters.eq("createdAt", at), Filters.lt("_id", query.after().id()))));
        }
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(filters)).sort(Sorts.orderBy(Sorts.descending("createdAt"), Sorts.descending("_id")))
                .limit(query.limit() + 1)).map(d -> mongo.read(d, Notification.class)).collectList();
    }

    @Override
    public Mono<Long> countUnread(String userId, FeedQuery query) {
        return Mono.from(mongo.collection(COLLECTION).countDocuments(Filters.and(filters(userId, query.forCount()))));
    }

    @Override
    public Mono<Void> markRead(String userId, Collection<String> ids, Instant at) {
        if (ids.isEmpty()) {
            return Mono.empty();
        }
        return Mono.from(mongo.collection(COLLECTION).updateMany(Filters.and(Filters.eq("userId", userId), Filters.in("_id", ids), Filters.eq("read", false)),
                Updates.combine(Updates.set("read", true), Updates.set(Mongo.DOC + ".readAt", at.toString())))).then();
    }

    @Override
    public Mono<Void> markAllRead(String userId, Instant at) {
        return Mono.from(mongo.collection(COLLECTION).updateMany(Filters.and(Filters.eq("userId", userId), Filters.eq("read", false)),
                Updates.combine(Updates.set("read", true), Updates.set(Mongo.DOC + ".readAt", at.toString())))).then();
    }

    private static List<Bson> filters(String userId, FeedQuery query) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("userId", userId));
        filters.add(Filters.in("accountId", query.accounts()));
        if (query.kind() != null) {
            filters.add(Filters.eq("kind", query.kind()));
        }
        if (query.unreadOnly()) {
            filters.add(Filters.eq("read", false));
        }
        return filters;
    }
}

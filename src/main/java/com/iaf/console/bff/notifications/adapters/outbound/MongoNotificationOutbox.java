package com.iaf.console.bff.notifications.adapters.outbound;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.notifications.domain.OutboxEntry;
import com.iaf.console.bff.notifications.ports.outbound.NotificationOutbox;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The outbox in the {@code notification_outbox} collection, shared by every replica: {@code _id}
 * the delivery's key ({@code <eventKey>:<userId>:<channel>}, so the first enqueue wins), the entry
 * under {@code doc}, and beside it {@code status}, {@code nextAttemptAt} and {@code leaseUntil} for
 * the claim, which takes one entry at a time with {@code findOneAndUpdate} so two replicas never
 * hold one. Entries go 30 days after they were queued (a TTL index on {@code expiresAt}).
 */
@Component
@Profile("mongo")
public class MongoNotificationOutbox implements NotificationOutbox, MongoIndexed {

    static final String COLLECTION = "notification_outbox";
    static final Duration KEPT = Duration.ofDays(30);

    private final Mongo mongo;

    public MongoNotificationOutbox(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("nextAttemptAt"))),
                new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<Boolean> enqueue(OutboxEntry entry) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(entry))).thenReturn(true)
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.just(false) : Mono.error(e));
    }

    @Override
    public Flux<OutboxEntry> claimDue(Instant now, Duration lease, int max) {
        Instant until = now.plus(lease);
        Mono<OutboxEntry> one = Mono.from(mongo.collection(COLLECTION).findOneAndUpdate(
                Filters.and(Filters.eq("status", OutboxEntry.PENDING), Filters.lte("nextAttemptAt", Mongo.date(now)),
                        Filters.or(Filters.eq("leaseUntil", null), Filters.lte("leaseUntil", Mongo.date(now)))),
                Updates.combine(Updates.set("leaseUntil", Mongo.date(until)), Updates.set(Mongo.DOC + ".leaseUntil", until.toString())),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("nextAttemptAt")).returnDocument(ReturnDocument.AFTER)))
                .map(d -> mongo.read(d, OutboxEntry.class));
        return claim(one, max);
    }

    /** Claims one entry after the other until {@code left} are held or none is due. */
    private static Flux<OutboxEntry> claim(Mono<OutboxEntry> one, int left) {
        if (left <= 0) {
            return Flux.empty();
        }
        return one.flatMapMany(entry -> Flux.concat(Flux.just(entry), Flux.defer(() -> claim(one, left - 1))));
    }

    @Override
    public Mono<Void> save(OutboxEntry entry) {
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", entry.key()), document(entry), new ReplaceOptions().upsert(true)))
                .then();
    }

    private Document document(OutboxEntry entry) {
        return new Document("_id", entry.key())
                .append("status", entry.status())
                .append("channel", entry.channel())
                .append("userId", entry.recipient().userId())
                .append("nextAttemptAt", Mongo.date(entry.nextAttemptAt()))
                .append("leaseUntil", Mongo.date(entry.leaseUntil()))
                .append("expiresAt", Mongo.date(entry.createdAt().plus(KEPT)))
                .append(Mongo.DOC, mongo.doc(entry));
    }
}

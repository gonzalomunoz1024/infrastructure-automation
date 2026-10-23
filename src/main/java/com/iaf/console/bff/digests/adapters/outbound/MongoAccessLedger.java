package com.iaf.console.bff.digests.adapters.outbound;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.digests.domain.AccessFact;
import com.iaf.console.bff.digests.domain.SubjectState;
import com.iaf.console.bff.digests.ports.outbound.AccessLedger;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The identity events' record in MongoDB, shared by every replica: {@code access_facts} holds one
 * document per event that granted something ({@code _id} the event's key, so a second record of
 * it is a duplicate key), its account and time, kept five weeks by a TTL index;
 * {@code identity_states} holds each subject's latest state ({@code _id}
 * {@code <account>:<aggregate>:<id>}, replaced only by a newer version) and, under
 * {@code _following}, when the BFF began following the events.
 */
@Component
@Profile("mongo")
public class MongoAccessLedger implements AccessLedger, MongoIndexed {

    static final String FACTS = "access_facts";
    static final String STATES = "identity_states";
    static final String FOLLOWING = "_following";
    static final Duration RETENTION = Duration.ofDays(35);

    private final Mongo mongo;

    public MongoAccessLedger(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(FACTS, List.of(
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("accountId"), Indexes.ascending("at"))),
                new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<SubjectState> findState(AccountScope scope, String aggregate, String aggregateId) {
        return Mono.from(mongo.collection(STATES).find(Filters.eq("_id", stateKey(scope.accountId(), aggregate, aggregateId))).first())
                .map(d -> mongo.read(d, SubjectState.class));
    }

    @Override
    public Mono<Void> record(String accountId, String eventKey, Instant at, List<AccessFact> facts, String aggregate, String aggregateId,
            SubjectState after) {
        Mono<Void> stored = facts.isEmpty() ? Mono.empty() : Mono.from(mongo.collection(FACTS).insertOne(new Document("_id", eventKey)
                .append("accountId", accountId).append("at", Mongo.date(at)).append("expiresAt", Mongo.date(at.plus(RETENTION)))
                .append("facts", facts.stream().map(mongo::doc).toList())))
                .then()
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.empty() : Mono.error(e));
        return stored.then(Mono.defer(() -> aggregate == null ? Mono.<Void>empty() : state(stateKey(accountId, aggregate, aggregateId), after)));
    }

    /** The subject's state replaced by a newer one, or forgotten. */
    private Mono<Void> state(String key, SubjectState after) {
        if (after == null) {
            return Mono.from(mongo.collection(STATES).deleteOne(Filters.eq("_id", key))).then();
        }
        return Mono.from(mongo.collection(STATES).updateOne(
                Filters.and(Filters.eq("_id", key), Filters.or(Filters.lt("version", after.version()), Filters.exists("version", false))),
                Updates.combine(Updates.set("version", after.version()), Updates.set(Mongo.DOC, mongo.doc(after))), new UpdateOptions().upsert(true)))
                .then()
                // a newer state is held: the upsert found no document to update and could not insert one with the same id
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e) ? Mono.empty() : Mono.error(e));
    }

    @Override
    public Mono<List<AccessFact>> list(AccountScope scope, Instant from, Instant to) {
        return Flux.from(mongo.collection(FACTS).find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.gte("at", Mongo.date(from)),
                Filters.lt("at", Mongo.date(to)))))
                .concatMapIterable(d -> {
                    List<AccessFact> out = new ArrayList<>();
                    for (Document fact : d.getList("facts", Document.class, List.of())) {
                        out.add(mongo.as(fact, AccessFact.class));
                    }
                    return out;
                })
                .collectList();
    }

    @Override
    public Mono<Instant> follow(Instant now) {
        return Mono.from(mongo.collection(STATES).findOneAndUpdate(Filters.eq("_id", FOLLOWING), Updates.setOnInsert("since", Mongo.date(now)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)))
                .map(d -> Mongo.instant(d.getDate("since")));
    }

    @Override
    public Mono<Instant> followingSince() {
        return Mono.from(mongo.collection(STATES).find(Filters.eq("_id", FOLLOWING)).first()).flatMap(d -> Mono.justOrEmpty(Mongo.instant(d.getDate("since"))));
    }

    private static String stateKey(String accountId, String aggregate, String aggregateId) {
        return accountId + ":" + aggregate + ":" + aggregateId;
    }
}

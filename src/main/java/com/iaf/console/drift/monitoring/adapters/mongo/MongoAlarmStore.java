package com.iaf.console.drift.monitoring.adapters.mongo;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.drift.monitoring.domain.Alarm;
import com.iaf.console.drift.monitoring.domain.AlarmQuery;
import com.iaf.console.drift.monitoring.ports.outbound.AlarmStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The alarms in MongoDB, collection {@code drift_alarms}: one document per account and alarm id
 * ({@code _id} {@code <accountId>:<alarmId>}, so the id is unique in the account), with the state's
 * rank beside the JSON so a page with the firing alarms first is one indexed read, and the wfrn so
 * the evaluation reads the alarms a resource at a time.
 */
public final class MongoAlarmStore implements AlarmStorePort, MongoIndexed {

    static final String COLLECTION = "drift_alarms";

    private final Mongo mongo;

    public MongoAlarmStore(Mongo mongo) {
        this.mongo = mongo;
    }

    static String id(String accountId, String alarmId) {
        return accountId + ":" + alarmId;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("accountId", "stateRank", "alarmId")),
                new IndexModel(Indexes.ascending("accountId", "wfrn")),
                new IndexModel(Indexes.ascending("wfrn", "accountId", "alarmId"))));
    }

    @Override
    public Mono<Alarm> find(AccountScope scope, String alarmId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", id(scope.accountId(), alarmId))).first()).map(this::read);
    }

    @Override
    public Mono<Page<Alarm>> list(AccountScope scope, AlarmQuery query) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("accountId", scope.accountId()));
        if (query.wfrn() != null) {
            filters.add(Filters.eq("wfrn", query.wfrn()));
        }
        if (query.state() != null) {
            filters.add(Filters.eq("stateRank", query.state().rank()));
        }
        if (query.after() != null) {
            long rank = query.after().sortLong();
            filters.add(Filters.or(Filters.gt("stateRank", rank),
                    Filters.and(Filters.eq("stateRank", rank), Filters.gt("alarmId", query.after().id()))));
        }
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(filters)).sort(Sorts.ascending("stateRank", "alarmId")).limit(query.limit() + 1))
                .map(this::read)
                .collectList()
                .map(fetched -> Page.from(fetched, query.limit(), a -> new Cursor((long) a.state().rank(), a.alarmId())));
    }

    @Override
    public Flux<Alarm> findAll() {
        return Flux.from(mongo.collection(COLLECTION).find().sort(Sorts.ascending("wfrn", "accountId", "alarmId"))).map(this::read);
    }

    @Override
    public Mono<Alarm> save(Alarm alarm) {
        Alarm next = alarm.withRev(alarm.rev() + 1);
        String id = id(alarm.accountId(), alarm.alarmId());
        if (alarm.rev() == 0) {
            return Mono.from(mongo.collection(COLLECTION).insertOne(toDocument(id, next)))
                    .thenReturn(next)
                    .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new Conflict(alarm.accountId(), alarm.alarmId()) : e);
        }
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.and(Filters.eq("_id", id), Filters.eq("rev", alarm.rev())), toDocument(id, next)))
                .flatMap(r -> r.getMatchedCount() == 1 ? Mono.just(next) : Mono.error(new Conflict(alarm.accountId(), alarm.alarmId())));
    }

    @Override
    public Mono<Boolean> delete(AccountScope scope, String alarmId) {
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.eq("_id", id(scope.accountId(), alarmId)))).map(r -> r.getDeletedCount() > 0);
    }

    private Document toDocument(String id, Alarm a) {
        return new Document("_id", id)
                .append("rev", a.rev())
                .append("accountId", a.accountId())
                .append("alarmId", a.alarmId())
                .append("wfrn", a.wfrn())
                .append("stateRank", a.state().rank())
                .append(Mongo.DOC, mongo.doc(a));
    }

    private Alarm read(Document d) {
        return mongo.read(d, Alarm.class).withRev(((Number) d.get("rev")).longValue());
    }
}

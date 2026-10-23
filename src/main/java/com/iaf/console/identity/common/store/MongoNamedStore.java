package com.iaf.console.identity.common.store;

import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What the Mongo stores of account-scoped, named records share (groups, roles, policies): one
 * document per record, {@code _id} the id, {@code accountId}, {@code name} and {@code version}
 * beside the record under {@code doc}; a unique index on account and name; optimistic writes on
 * the version; pages in id order.
 */
public final class MongoNamedStore<T> {

    private final Mongo mongo;
    private final String collection;
    private final Class<T> type;
    private final Keyed<T> keys;
    private final String what;

    public MongoNamedStore(Mongo mongo, String collection, Class<T> type, Keyed<T> keys, String what) {
        this.mongo = mongo;
        this.collection = collection;
        this.type = type;
        this.keys = keys;
        this.what = what;
    }

    public Mono<Void> ensureIndexes(List<IndexModel> extra) {
        List<IndexModel> models = new java.util.ArrayList<>(List.of(
                new IndexModel(Indexes.ascending("accountId", "name"), new IndexOptions().unique(true)),
                new IndexModel(Indexes.ascending("accountId", "_id"))));
        models.addAll(extra);
        return mongo.indexes(collection, models);
    }

    public Mono<T> insert(T record, Document extraFields) {
        return Mono.from(mongo.collection(collection).insertOne(document(record, extraFields)))
                .thenReturn(record)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("a " + what + " named '" + keys.name(record) + "' exists") : e);
    }

    public Mono<T> update(T record, long expectedVersion, Document extraFields) {
        Bson filter = Filters.and(Filters.eq("_id", keys.id(record)), Filters.eq("accountId", keys.accountId(record)), Filters.eq("version", expectedVersion));
        return Mono.from(mongo.collection(collection).findOneAndReplace(filter, document(record, extraFields),
                        new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)))
                .map(this::read)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("a " + what + " named '" + keys.name(record) + "' exists") : e);
    }

    public Mono<Boolean> delete(AccountScope scope, String id) {
        return Mono.from(mongo.collection(collection).deleteOne(Filters.and(Filters.eq("_id", id), Filters.eq("accountId", scope.accountId()))))
                .map(r -> r.getDeletedCount() > 0);
    }

    public Mono<T> find(AccountScope scope, String id) {
        return Mono.from(mongo.collection(collection).find(Filters.and(Filters.eq("_id", id), Filters.eq("accountId", scope.accountId()))).first())
                .map(this::read);
    }

    public Mono<T> findByName(AccountScope scope, String name) {
        return Mono.from(mongo.collection(collection).find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("name", name))).first())
                .map(this::read);
    }

    public Mono<Page<T>> list(AccountScope scope, int limit, String after, Bson extraFilter) {
        Bson filter = Filters.eq("accountId", scope.accountId());
        if (extraFilter != null) {
            filter = Filters.and(filter, extraFilter);
        }
        if (after != null) {
            Cursor cursor = CursorPaging.decode(after);
            filter = Filters.and(filter, Filters.gt("_id", cursor.id()));
        }
        return Flux.from(mongo.collection(collection).find(filter).sort(Sorts.ascending("_id")).limit(limit + 1))
                .map(this::read)
                .collectList()
                .map(fetched -> Page.from(fetched, limit, r -> new Cursor(keys.id(r), keys.id(r))));
    }

    public Flux<T> findAll(AccountScope scope) {
        return Flux.from(mongo.collection(collection).find(Filters.eq("accountId", scope.accountId())).sort(Sorts.ascending("_id")))
                .map(this::read);
    }

    public Flux<T> findWhere(Bson filter) {
        return Flux.from(mongo.collection(collection).find(filter).sort(Sorts.ascending("accountId", "_id"))).map(this::read);
    }

    public Flux<T> findEverything() {
        return Flux.from(mongo.collection(collection).find().sort(Sorts.ascending("accountId", "_id"))).map(this::read);
    }

    public Mongo mongo() {
        return mongo;
    }

    public String collection() {
        return collection;
    }

    /** The record: {@code accountId} is write-only on the records (the API never shows it), so it comes from the field beside {@code doc}. */
    private T read(Document d) {
        Document doc = d.get(Mongo.DOC, Document.class);
        doc.put("accountId", d.getString("accountId"));
        return mongo.as(doc, type);
    }

    private Document document(T record, Document extraFields) {
        Document d = new Document("_id", keys.id(record))
                .append("accountId", keys.accountId(record))
                .append("name", keys.name(record))
                .append("version", keys.version(record));
        if (extraFields != null) {
            d.putAll(extraFields);
        }
        return d.append(Mongo.DOC, mongo.doc(record));
    }
}

package com.iaf.console.identity.accesskeys.adapters.mongo;

import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.identity.accesskeys.domain.AccessKey;
import com.iaf.console.identity.accesskeys.ports.outbound.AccessKeyStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Collection {@code access_keys}: {@code _id} the key id; the hashes stay inside the record. */
public final class MongoAccessKeyStore implements AccessKeyStore, MongoIndexed {

    static final String COLLECTION = "access_keys";

    private final Mongo mongo;

    public MongoAccessKeyStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("accountId", "userId", "_id")),
                new IndexModel(Indexes.ascending("accountId", "_id"))));
    }

    @Override
    public Mono<AccessKey> insert(AccessKey key) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(key))).thenReturn(key)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("the key id exists") : e);
    }

    @Override
    public Mono<AccessKey> update(AccessKey key, long expectedVersion) {
        Bson filter = Filters.and(Filters.eq("_id", key.keyId()), Filters.eq("version", expectedVersion));
        return Mono.from(mongo.collection(COLLECTION).findOneAndReplace(filter, document(key), new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)))
                .map(this::read);
    }

    @Override
    public Mono<AccessKey> find(AccountScope scope, String keyId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", keyId), Filters.eq("accountId", scope.accountId()))).first())
                .map(this::read);
    }

    @Override
    public Mono<Page<AccessKey>> list(AccountScope scope, String userId, int limit, String after) {
        Bson filter = Filters.eq("accountId", scope.accountId());
        if (userId != null) {
            filter = Filters.and(filter, Filters.eq("userId", userId));
        }
        if (after != null) {
            filter = Filters.and(filter, Filters.gt("_id", CursorPaging.decode(after).id()));
        }
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(Sorts.ascending("_id")).limit(limit + 1)).map(this::read).collectList()
                .map(fetched -> Page.from(fetched, limit, k -> new Cursor(k.keyId(), k.keyId())));
    }

    @Override
    public Flux<AccessKey> findByUser(AccountScope scope, String userId) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("userId", userId)))
                .sort(Sorts.ascending("_id"))).map(this::read);
    }

    @Override
    public Flux<AccessKey> findAll(AccountScope scope) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.eq("accountId", scope.accountId())).sort(Sorts.ascending("_id"))).map(this::read);
    }

    @Override
    public Mono<AccessKey> findByKeyId(String keyId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", keyId)).first()).map(this::read);
    }

    private AccessKey read(Document d) {
        Document doc = d.get(Mongo.DOC, Document.class);
        AccessKey key = mongo.as(doc, AccessKey.class);
        // the hashes are @JsonIgnore on the record, so they travel in their own fields
        return new AccessKey(key.keyId(), d.getString("accountId"), key.userId(), key.description(), d.getString("secretHash"), d.getString("previousSecretHash"),
                key.graceUntil(), key.revokedAt(), key.createdAt(), key.expiresAt(), key.lastUsedAt(), key.version());
    }

    private Document document(AccessKey k) {
        return new Document("_id", k.keyId())
                .append("accountId", k.accountId())
                .append("userId", k.userId())
                .append("version", k.version())
                .append("secretHash", k.secretHash())
                .append("previousSecretHash", k.previousSecretHash())
                .append(Mongo.DOC, mongo.doc(k));
    }
}

package com.iaf.console.identity.accounts.adapters.mongo;

import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.domain.AccountStatus;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
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
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Collection {@code accounts}: {@code _id} the account id, the record under {@code doc}, name and status beside it. */
public final class MongoAccountStore implements AccountStore, MongoIndexed {

    static final String COLLECTION = "accounts";

    private final Mongo mongo;

    public MongoAccountStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("name"), new IndexOptions().unique(true)),
                new IndexModel(Indexes.ascending("status", "_id"))));
    }

    @Override
    public Mono<Account> insert(Account account) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(account)))
                .thenReturn(account)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("an account with this id or name exists") : e);
    }

    @Override
    public Mono<Account> update(Account account, long expectedVersion) {
        Bson filter = Filters.and(Filters.eq("_id", account.accountId()), Filters.eq("version", expectedVersion));
        return Mono.from(mongo.collection(COLLECTION).findOneAndReplace(filter, document(account),
                        new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)))
                .map(d -> mongo.read(d, Account.class))
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("an account with this name exists") : e);
    }

    @Override
    public Mono<Account> find(AccountScope scope) {
        return findById(scope.accountId());
    }

    @Override
    public Mono<Account> findById(String accountId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", accountId)).first()).map(d -> mongo.read(d, Account.class));
    }

    @Override
    public Mono<Boolean> existsByName(String name) {
        return Mono.from(mongo.collection(COLLECTION).countDocuments(Filters.eq("name", name))).map(n -> n > 0);
    }

    @Override
    public Mono<Page<Account>> list(AccountStatus status, int limit, String after) {
        Bson filter = Filters.empty();
        if (status != null) {
            filter = Filters.eq("status", status.name());
        }
        if (after != null) {
            Cursor cursor = CursorPaging.decode(after);
            filter = Filters.and(filter, Filters.gt("_id", cursor.id()));
        }
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(Sorts.ascending("_id")).limit(limit + 1))
                .map(d -> mongo.read(d, Account.class))
                .collectList()
                .map(fetched -> Page.from(fetched, limit, a -> new Cursor(a.accountId(), a.accountId())));
    }

    @Override
    public Mono<List<Account>> listByIds(List<String> accountIds) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.in("_id", accountIds)).sort(Sorts.ascending("_id")))
                .map(d -> mongo.read(d, Account.class)).collectList();
    }

    @Override
    public Flux<Account> findAll() {
        return Flux.from(mongo.collection(COLLECTION).find().sort(Sorts.ascending("_id"))).map(d -> mongo.read(d, Account.class));
    }

    private Document document(Account a) {
        return new Document("_id", a.accountId())
                .append("name", a.name())
                .append("status", a.status().name())
                .append("owningTeam", a.owningTeam())
                .append("version", a.version())
                .append("createdAt", Mongo.date(a.createdAt()))
                .append(Mongo.DOC, mongo.doc(a));
    }
}

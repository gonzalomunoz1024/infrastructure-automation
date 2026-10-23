package com.iaf.console.identity.principals.adapters.mongo;

import java.util.List;
import java.util.regex.Pattern;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
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

/**
 * Collection {@code memberships}: {@code _id} is {@code <accountId>:<userId>}, with the account,
 * user, email, name, groups and version beside the record; pages are in user id order.
 */
public final class MongoMembershipStore implements MembershipStore, MongoIndexed {

    static final String COLLECTION = "memberships";

    private final Mongo mongo;

    public MongoMembershipStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("accountId", "userId")),
                new IndexModel(Indexes.ascending("accountId", "groupIds")),
                new IndexModel(Indexes.ascending("userId"))));
    }

    static String id(String accountId, String userId) {
        return accountId + ":" + userId;
    }

    @Override
    public Mono<Membership> insert(Membership m) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(m)))
                .thenReturn(m)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("the user is already in the account") : e);
    }

    @Override
    public Mono<Membership> update(Membership m, long expectedVersion) {
        Bson filter = Filters.and(Filters.eq("_id", id(m.accountId(), m.userId())), Filters.eq("version", expectedVersion));
        return Mono.from(mongo.collection(COLLECTION).findOneAndReplace(filter, document(m), new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)))
                .map(d -> mongo.read(d, Membership.class));
    }

    @Override
    public Mono<Boolean> delete(AccountScope scope, String userId) {
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.eq("_id", id(scope.accountId(), userId)))).map(r -> r.getDeletedCount() > 0);
    }

    @Override
    public Mono<Membership> find(AccountScope scope, String userId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", id(scope.accountId(), userId))).first()).map(d -> mongo.read(d, Membership.class));
    }

    @Override
    public Mono<Page<Membership>> list(AccountScope scope, String query, int limit, String after) {
        Bson filter = Filters.eq("accountId", scope.accountId());
        if (query != null && !query.isBlank()) {
            String prefix = "^" + Pattern.quote(query.trim());
            filter = Filters.and(filter, Filters.or(Filters.regex("email", prefix, "i"), Filters.regex("name", prefix, "i")));
        }
        if (after != null) {
            Cursor cursor = CursorPaging.decode(after);
            filter = Filters.and(filter, Filters.gt("userId", cursor.id()));
        }
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(Sorts.ascending("userId")).limit(limit + 1))
                .map(d -> mongo.read(d, Membership.class))
                .collectList()
                .map(fetched -> Page.from(fetched, limit, m -> new Cursor(m.userId(), m.userId())));
    }

    @Override
    public Flux<Membership> findAll(AccountScope scope) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.eq("accountId", scope.accountId())).sort(Sorts.ascending("userId")))
                .map(d -> mongo.read(d, Membership.class));
    }

    @Override
    public Flux<Membership> findByGroup(AccountScope scope, String groupId) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("groupIds", groupId)))
                .sort(Sorts.ascending("userId"))).map(d -> mongo.read(d, Membership.class));
    }

    @Override
    public Mono<Long> countByGroup(AccountScope scope, String groupId) {
        return Mono.from(mongo.collection(COLLECTION).countDocuments(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("groupIds", groupId))));
    }

    @Override
    public Flux<Membership> findByUser(String userId) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.eq("userId", userId)).sort(Sorts.ascending("accountId")))
                .map(d -> mongo.read(d, Membership.class));
    }

    @Override
    public Flux<Membership> findEverything() {
        return Flux.from(mongo.collection(COLLECTION).find().sort(Sorts.ascending("_id"))).map(d -> mongo.read(d, Membership.class));
    }

    @Override
    public Mono<Void> updateUserFields(String userId, String email, String name) {
        return findByUser(userId)
                .concatMap(m -> Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", id(m.accountId(), m.userId())),
                        document(m.withUserFields(email, name)))))
                .then();
    }

    private Document document(Membership m) {
        return new Document("_id", id(m.accountId(), m.userId()))
                .append("accountId", m.accountId())
                .append("userId", m.userId())
                .append("email", m.email())
                .append("name", m.name())
                .append("status", m.status().name())
                .append("groupIds", m.groupIds())
                .append("version", m.version())
                .append(Mongo.DOC, mongo.doc(m));
    }
}

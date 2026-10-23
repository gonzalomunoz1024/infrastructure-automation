package com.iaf.console.identity.principals.adapters.mongo;

import java.util.List;
import java.util.Locale;

import org.bson.Document;

import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Collection {@code users}: {@code _id} the user id, unique email (compared lower-cased), unique {@code identity} (issuer and subject) when linked. */
public final class MongoUserStore implements UserStore, MongoIndexed {

    static final String COLLECTION = "users";

    private final Mongo mongo;

    public MongoUserStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("email"), new IndexOptions().unique(true)),
                new IndexModel(Indexes.ascending("identity"), new IndexOptions().unique(true).sparse(true))));
    }

    @Override
    public Mono<User> insert(User user) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(user)))
                .thenReturn(user)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("a user with this email or identity exists") : e);
    }

    @Override
    public Mono<User> save(User user) {
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", user.userId()), document(user), new ReplaceOptions().upsert(true)))
                .thenReturn(user)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("a user with this email or identity exists") : e);
    }

    @Override
    public Mono<User> findById(String userId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", userId)).first()).map(d -> mongo.read(d, User.class));
    }

    @Override
    public Mono<User> findByEmail(String email) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("email", lower(email))).first()).map(d -> mongo.read(d, User.class));
    }

    @Override
    public Mono<User> findByIssuerAndSubject(String issuer, String subject) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("identity", identity(issuer, subject))).first()).map(d -> mongo.read(d, User.class));
    }

    @Override
    public Flux<User> findByIds(List<String> userIds) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.in("_id", userIds))).map(d -> mongo.read(d, User.class));
    }

    @Override
    public Flux<User> findAll() {
        return Flux.from(mongo.collection(COLLECTION).find().sort(Sorts.ascending("_id"))).map(d -> mongo.read(d, User.class));
    }

    private Document document(User u) {
        Document d = new Document("_id", u.userId()).append("email", lower(u.email())).append("status", u.status().name());
        if (u.issuer() != null) {
            d.append("identity", identity(u.issuer(), u.subject()));
        }
        return d.append(Mongo.DOC, mongo.doc(u));
    }

    /** The linked identity as one indexed field (unique and sparse, so unlinked users do not collide). */
    private static String identity(String issuer, String subject) {
        return issuer + "|" + subject;
    }

    private static String lower(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}

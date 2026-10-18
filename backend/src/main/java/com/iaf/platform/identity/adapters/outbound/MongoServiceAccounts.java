package com.iaf.platform.identity.adapters.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

/**
 * The service accounts of the {@code mongo} profile: one document per account in
 * {@code service_accounts}, keyed by its id, its doc the {@link ServiceAccount}, found by the
 * SHA-256 of its token ({@code tokenHash}, unique among the accounts that have one: an account
 * without a hash has no such field, so any number of them fit). The token itself is shown once
 * when the account is made and is never stored; neither is anything that could rebuild it.
 */
@Component
@Primary
@Profile("mongo")
public class MongoServiceAccounts implements ServiceAccountStore {

    static final String COLLECTION = "service_accounts";

    private final Mongo mongo;

    public MongoServiceAccounts(Mongo mongo) {
        this.mongo = mongo;
        // partial on $exists rather than sparse or $type: an equality on the hash uses this index
        mongo.index(COLLECTION, Indexes.ascending("tokenHash"), new IndexOptions().unique(true).partialFilterExpression(Filters.exists("tokenHash")));
    }

    @Override
    public void save(ServiceAccount account) {
        Document d = new Document("_id", account.id());
        if (account.tokenHash() != null) {
            d.append("tokenHash", account.tokenHash());
        }
        d.append("createdNs", Mongo.nanos(account.createdAt())).append(Mongo.DOC, mongo.doc(account));
        mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", account.id()), d, new ReplaceOptions().upsert(true));
    }

    @Override
    public Optional<ServiceAccount> byTokenHash(String hash) {
        if (hash == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.collection(COLLECTION).find(Filters.eq("tokenHash", hash)).first())
                .map(d -> mongo.read(d, ServiceAccount.class));
    }

    /** Oldest first, ties by id; an account without a creation time comes last. */
    @Override
    public List<ServiceAccount> all() {
        List<ServiceAccount> dated = new ArrayList<>();
        List<ServiceAccount> undated = new ArrayList<>();
        for (Document d : mongo.collection(COLLECTION).find().sort(Sorts.ascending("createdNs", "_id"))) {
            (d.get("createdNs") == null ? undated : dated).add(mongo.read(d, ServiceAccount.class));
        }
        dated.addAll(undated);
        return dated;
    }
}

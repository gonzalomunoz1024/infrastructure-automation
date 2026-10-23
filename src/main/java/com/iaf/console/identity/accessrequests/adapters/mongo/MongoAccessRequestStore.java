package com.iaf.console.identity.accessrequests.adapters.mongo;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.identity.accessrequests.domain.AccessRequest;
import com.iaf.console.identity.accessrequests.ports.outbound.AccessRequestStore;
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

/** Collection {@code access_requests}: {@code _id} the request id, the account, requester, role and status beside the record. */
public final class MongoAccessRequestStore implements AccessRequestStore, MongoIndexed {

    static final String COLLECTION = "access_requests";

    private final Mongo mongo;

    public MongoAccessRequestStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("accountId", "requesterId", "role", "status")),
                new IndexModel(Indexes.ascending("accountId", "status", "_id")),
                new IndexModel(Indexes.ascending("status", "requestedAt"))));
    }

    @Override
    public Mono<AccessRequest> insert(AccessRequest r) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(r))).thenReturn(r)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("the request id exists") : e);
    }

    @Override
    public Mono<AccessRequest> update(AccessRequest r, long expectedVersion) {
        Bson filter = Filters.and(Filters.eq("_id", r.requestId()), Filters.eq("version", expectedVersion));
        return Mono.from(mongo.collection(COLLECTION).findOneAndReplace(filter, document(r), new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)))
                .map(this::read);
    }

    @Override
    public Mono<AccessRequest> find(AccountScope scope, String requestId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", requestId), Filters.eq("accountId", scope.accountId()))).first())
                .map(this::read);
    }

    @Override
    public Mono<AccessRequest> findPending(AccountScope scope, String userId, String role) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("requesterId", userId),
                Filters.eq("role", role), Filters.eq("status", AccessRequest.Status.pending.name()))).sort(Sorts.ascending("_id")).first()).map(this::read);
    }

    @Override
    public Flux<AccessRequest> findPendingBefore(Instant cutoff) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("status", AccessRequest.Status.pending.name()),
                Filters.lt("requestedAt", java.util.Date.from(cutoff)))).sort(Sorts.ascending("_id"))).map(this::read);
    }

    @Override
    public Mono<Page<AccessRequest>> list(AccountScope scope, String requesterUserId, AccessRequest.Status status, int limit, String after) {
        Bson filter = Filters.eq("accountId", scope.accountId());
        if (requesterUserId != null) {
            filter = Filters.and(filter, Filters.eq("requesterId", requesterUserId));
        }
        if (status != null) {
            filter = Filters.and(filter, Filters.eq("status", status.name()));
        }
        if (after != null) {
            filter = Filters.and(filter, Filters.gt("_id", CursorPaging.decode(after).id()));
        }
        return Flux.from(mongo.collection(COLLECTION).find(filter).sort(Sorts.ascending("_id")).limit(limit + 1)).map(this::read).collectList()
                .map(fetched -> Page.from(fetched, limit, r -> new Cursor(r.requestId(), r.requestId())));
    }

    private AccessRequest read(Document d) {
        AccessRequest r = mongo.read(d, AccessRequest.class);
        return new AccessRequest(r.requestId(), r.accountId(), r.requester(), r.action(), r.resource(), r.role(), r.reason(), r.status(), r.requestedAt(),
                r.decidedAt(), r.decidedBy(), r.comment(), r.approvers(), ((Number) d.get("version")).longValue());
    }

    private Document document(AccessRequest r) {
        return new Document("_id", r.requestId())
                .append("accountId", r.accountId())
                .append("requesterId", r.requester().userId())
                .append("role", r.role())
                .append("status", r.status().name())
                .append("requestedAt", java.util.Date.from(r.requestedAt()))
                .append("version", r.version())
                .append(Mongo.DOC, mongo.doc(r));
    }
}

package com.iaf.platform.workflows.adapters.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

/**
 * The change requests of the {@code mongo} profile: one document per change request in
 * {@code change_requests}, keyed by its id, newest first (created at, undated ones last, then id).
 *
 * <p>A merge or a close applies once. The handlers check that a change request is open before
 * deciding on it, but two instances (or two requests) can both pass that check; the decision is
 * then written in one write whose filter names the open status, so only the first lands and the
 * other is refused with a 409 before it publishes or tells anyone, as the check itself would
 * have refused it.
 */
@Component
@Primary
@Profile("mongo")
public class MongoChangeRequests implements ChangeRequestPort {

    static final String COLLECTION = "change_requests";

    private static final String OPEN = ChangeRequest.Status.OPEN.json();
    private static final Bson NEWEST_FIRST = Sorts.orderBy(Sorts.descending("createdNs"), Sorts.ascending("_id"));

    private final Mongo mongo;

    public MongoChangeRequests(Mongo mongo) {
        this.mongo = mongo;
        mongo.index(COLLECTION, Indexes.compoundIndex(Indexes.descending("createdNs"), Indexes.ascending("_id")));
    }

    /** Newest first; a change request without a creation time sorts last (a missing number is the lowest), then by id. */
    @Override
    public List<ChangeRequest> all() {
        List<ChangeRequest> out = new ArrayList<>();
        mongo.collection(COLLECTION).find().sort(NEWEST_FIRST).forEach(d -> out.add(read(d)));
        return out;
    }

    @Override
    public Optional<ChangeRequest> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(mongo.collection(COLLECTION).find(Filters.eq("_id", id)).first()).map(this::read);
    }

    /**
     * An open change request (a new one, or one put back open when publishing its merge failed)
     * replaces whatever has its id. A merged or closed one replaces only an open one: when
     * another merge or close came first, the filter matches nothing and the upsert collides with
     * the stored id, and that duplicate key is a {@link ConflictException} naming the status the
     * change request has now. One saved already decided under a new id (the demo history's) is
     * inserted.
     */
    @Override
    public void save(ChangeRequest cr) {
        Document d = new Document("_id", cr.id())
                .append("name", cr.name())
                .append("status", cr.status() == null ? null : cr.status().json())
                .append("createdAt", Mongo.date(cr.createdAt()))
                .append("createdNs", Mongo.nanos(cr.createdAt()))
                .append(Mongo.DOC, mongo.doc(cr));
        boolean decision = cr.status() != null && cr.status() != ChangeRequest.Status.OPEN;
        Bson filter = decision ? Filters.and(Filters.eq("_id", cr.id()), Filters.eq("status", OPEN)) : Filters.eq("_id", cr.id());
        try {
            mongo.collection(COLLECTION).replaceOne(filter, d, new ReplaceOptions().upsert(true));
        } catch (MongoException e) {
            if (!decision || !Mongo.duplicateKey(e)) {
                throw e;
            }
            String now = get(cr.id()).map(ChangeRequest::status).map(ChangeRequest.Status::json).orElse("decided already");
            throw new ConflictException("change request " + cr.id() + " is " + now);
        }
    }

    private ChangeRequest read(Document stored) {
        return mongo.read(stored, ChangeRequest.class);
    }
}

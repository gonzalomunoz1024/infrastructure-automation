package com.iaf.console.identity.iam.adapters.mongo;

import java.util.List;

import org.bson.Document;

import com.iaf.console.identity.iam.domain.Guardrail;
import com.iaf.console.identity.iam.ports.outbound.GuardrailStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
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

/** Collection {@code guardrails}: {@code _id}, a unique {@code name} and {@code version} beside the record under {@code doc}. */
public final class MongoGuardrailStore implements GuardrailStore, MongoIndexed {

    private static final String COLLECTION = "guardrails";

    private final Mongo mongo;

    public MongoGuardrailStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("name"), new IndexOptions().unique(true))));
    }

    @Override
    public Mono<Guardrail> insert(Guardrail guardrail) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(guardrail)))
                .thenReturn(guardrail)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("a guardrail named '" + guardrail.name() + "' exists") : e);
    }

    @Override
    public Mono<Guardrail> update(Guardrail guardrail, long expectedVersion) {
        return Mono.from(mongo.collection(COLLECTION).findOneAndReplace(
                        Filters.and(Filters.eq("_id", guardrail.guardrailId()), Filters.eq("version", expectedVersion)), document(guardrail),
                        new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)))
                .map(this::read)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e) ? new ConflictException("a guardrail named '" + guardrail.name() + "' exists") : e);
    }

    @Override
    public Mono<Boolean> delete(String guardrailId) {
        return Mono.from(mongo.collection(COLLECTION).deleteOne(Filters.eq("_id", guardrailId))).map(r -> r.getDeletedCount() > 0);
    }

    @Override
    public Mono<Guardrail> findById(String guardrailId) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", guardrailId)).first()).map(this::read);
    }

    @Override
    public Flux<Guardrail> findAll() {
        return Flux.from(mongo.collection(COLLECTION).find().sort(Sorts.ascending("_id"))).map(this::read);
    }

    private Guardrail read(Document d) {
        return mongo.as(d.get(Mongo.DOC, Document.class), Guardrail.class);
    }

    private Document document(Guardrail guardrail) {
        return new Document("_id", guardrail.guardrailId())
                .append("name", guardrail.name())
                .append("version", guardrail.version())
                .append(Mongo.DOC, mongo.doc(guardrail));
    }
}

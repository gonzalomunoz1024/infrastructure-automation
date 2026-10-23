package com.iaf.console.state.stack.adapters.mongo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.application.InventoryService;
import com.iaf.console.state.stack.domain.Stack;
import com.iaf.console.state.stack.domain.StackQuery;
import com.iaf.console.state.stack.domain.StackState;
import com.iaf.console.state.stack.ports.outbound.StackStorePort;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stacks in MongoDB, collection {@code stacks}: {@code _id} the wfrn, the fields the queries filter
 * on beside the stack itself, and {@code liveName} (the name while the stack is not deleted) under a
 * unique partial index, so two live stacks of an account never share a name and a deleted one frees
 * it. A deleted stack expires with its {@code expiresAt} (TTL).
 */
public final class MongoStacks implements StackStorePort, MongoIndexed {

    public static final String COLLECTION = "stacks";

    private final Mongo mongo;

    public MongoStacks(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(
                new IndexModel(Indexes.ascending("accountId", "region", "liveName"),
                        new IndexOptions().unique(true).name("live_name").partialFilterExpression(Filters.exists("liveName"))),
                new IndexModel(Indexes.compoundIndex(Indexes.ascending("accountId", "region"), Indexes.descending("createdAtNs", "_id"))),
                new IndexModel(Indexes.ascending("inFlight", "updatedAtNs"), new IndexOptions().partialFilterExpression(Filters.eq("inFlight", true))),
                new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<Stack> insert(Stack stack) {
        return Mono.from(mongo.collection(COLLECTION).insertOne(document(stack))).thenReturn(stack)
                .onErrorMap(MongoException.class, e -> Mongo.duplicateKey(e)
                        ? new ConflictException("stack.nameTaken: the account has a stack named " + stack.name(), Map.of("name", stack.name()))
                        : e);
    }

    @Override
    public Mono<Stack> update(Stack next, long expectedVersion) {
        return Mono.from(mongo.collection(COLLECTION).replaceOne(Filters.and(Filters.eq("_id", next.wfrn()), Filters.eq("version", expectedVersion)),
                document(next)))
                .flatMap(result -> result.getMatchedCount() > 0 ? Mono.just(next)
                        : Mono.error(new ConflictException(InventoryService.VERSION_CONFLICT + ": stack " + next.wfrn() + " moved on")));
    }

    @Override
    public Mono<Stack> find(AccountScope scope, String wfrn) {
        return Mono.from(mongo.collection(COLLECTION)
                .find(Filters.and(Filters.eq("_id", wfrn), Filters.eq("accountId", scope.accountId()), Filters.eq("region", scope.region()))).first())
                .map(d -> mongo.read(d, Stack.class));
    }

    @Override
    public Mono<Stack> findByWfrn(String wfrn) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", wfrn)).first()).map(d -> mongo.read(d, Stack.class));
    }

    @Override
    public Mono<Page<Stack>> list(AccountScope scope, StackQuery q) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("accountId", scope.accountId()));
        filters.add(Filters.eq("region", scope.region()));
        if (q.state() != null) {
            filters.add(Filters.eq("state", q.state().wire()));
        }
        if (q.blueprint() != null) {
            filters.add(Filters.eq("blueprint", q.blueprint()));
        }
        if (q.namePrefix() != null) {
            filters.add(Filters.regex("name", "^" + Pattern.quote(q.namePrefix())));
        }
        if (q.after() != null) {
            long at = Mongo.nanos(Instant.parse(String.valueOf(q.after().sortValue())));
            filters.add(Filters.or(Filters.lt("createdAtNs", at), Filters.and(Filters.eq("createdAtNs", at), Filters.lt("_id", q.after().id()))));
        }
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(filters)).sort(Sorts.descending("createdAtNs", "_id")).limit(q.limit() + 1))
                .map(d -> mongo.read(d, Stack.class))
                .collectList()
                .map(list -> Page.from(list, q.limit(), s -> new Cursor(s.createdAt().toString(), s.wfrn())));
    }

    @Override
    public Flux<Stack> listInFlight(int limit) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.eq("inFlight", true)).sort(Sorts.ascending("updatedAtNs")).limit(limit))
                .map(d -> mongo.read(d, Stack.class));
    }

    private Document document(Stack s) {
        Document d = new Document("_id", s.wfrn())
                .append("accountId", s.accountId())
                .append("region", s.region())
                .append("name", s.name())
                .append("blueprint", s.blueprint())
                .append("state", s.state().wire())
                .append("inFlight", s.inFlight())
                .append("version", s.version())
                .append("createdAtNs", Mongo.nanos(s.createdAt()))
                .append("updatedAtNs", Mongo.nanos(s.updatedAt()))
                .append("expiresAt", s.state() == StackState.DELETED && s.expiresAt() != null ? Mongo.date(s.expiresAt()) : null)
                .append(Mongo.DOC, mongo.doc(s));
        if (!s.deleted()) {
            d.append("liveName", s.name());
        }
        return d;
    }
}

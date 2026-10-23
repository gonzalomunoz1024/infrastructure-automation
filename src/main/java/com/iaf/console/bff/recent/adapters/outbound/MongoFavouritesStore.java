package com.iaf.console.bff.recent.adapters.outbound;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.recent.domain.Favourite;
import com.iaf.console.bff.recent.domain.Favourites;
import com.iaf.console.bff.recent.ports.outbound.FavouritesStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Mono;

/**
 * Favourites in the {@code favourites} collection, one document per account and person
 * ({@code _id} {@code <account>:<user>}) holding the favourites in {@code items}, so the limit
 * holds under concurrent puts: a favourite already there is updated in place, and a new one is
 * pushed only while the list has fewer than {@code max} items and not that wfrn.
 */
@Component
@Profile("mongo")
public class MongoFavouritesStore implements FavouritesStore {

    static final String COLLECTION = "favourites";

    private final Mongo mongo;

    public MongoFavouritesStore(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<List<Favourite>> list(AccountScope scope) {
        return Mono.from(mongo.collection(COLLECTION).find(Filters.eq("_id", id(scope))).first()).map(this::items).defaultIfEmpty(List.of());
    }

    @Override
    public Mono<Favourites.Put> put(AccountScope scope, Favourite favourite, int max) {
        return inPlace(scope, favourite).switchIfEmpty(Mono.defer(() -> pushed(scope, favourite, max)))
                .onErrorResume(MongoException.class, e -> Mongo.duplicateKey(e)
                        // the document is there but full, or the wfrn arrived meanwhile
                        ? inPlace(scope, favourite).switchIfEmpty(Mono.defer(() -> list(scope).map(items -> new Favourites.Put(items, null, true))))
                        : Mono.error(e));
    }

    @Override
    public Mono<Boolean> remove(AccountScope scope, String wfrn) {
        return Mono.from(mongo.collection(COLLECTION).updateOne(Filters.eq("_id", id(scope)), Updates.pull("items", new Document("wfrn", wfrn))))
                .map(result -> result.getModifiedCount() > 0);
    }

    /** The favourite updated where it is, keeping when it was added; empty when the person has no such favourite. */
    private Mono<Favourites.Put> inPlace(AccountScope scope, Favourite favourite) {
        return Mono.from(mongo.collection(COLLECTION).findOneAndUpdate(
                Filters.and(Filters.eq("_id", id(scope)), Filters.eq("items.wfrn", favourite.wfrn())),
                Updates.combine(Updates.set("items.$.type", favourite.type()), Updates.set("items.$.name", favourite.name())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)))
                .map(this::items)
                .map(items -> new Favourites.Put(items, items.stream().filter(f -> f.wfrn().equals(favourite.wfrn())).findFirst().orElse(favourite), false));
    }

    /** The favourite pushed while there is room; a duplicate key when the document exists but is full or has the wfrn. */
    private Mono<Favourites.Put> pushed(AccountScope scope, Favourite favourite, int max) {
        return Mono.from(mongo.collection(COLLECTION).findOneAndUpdate(
                Filters.and(Filters.eq("_id", id(scope)), Filters.ne("items.wfrn", favourite.wfrn()), Filters.exists("items." + (max - 1), false)),
                Updates.combine(Updates.push("items", mongo.doc(favourite)), Updates.setOnInsert("accountId", scope.accountId()),
                        Updates.setOnInsert("userId", scope.principal())),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)))
                .map(this::items)
                .map(items -> new Favourites.Put(items, favourite, false));
    }

    private List<Favourite> items(Document stored) {
        List<Favourite> out = new ArrayList<>();
        for (Document item : stored.getList("items", Document.class, List.of())) {
            out.add(mongo.as(item, Favourite.class));
        }
        out.sort(Favourites.NEWEST_FIRST);
        return List.copyOf(out);
    }

    private static String id(AccountScope scope) {
        return scope.accountId() + ":" + scope.principal();
    }
}

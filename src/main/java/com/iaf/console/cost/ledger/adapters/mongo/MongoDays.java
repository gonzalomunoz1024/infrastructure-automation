package com.iaf.console.cost.ledger.adapters.mongo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.iaf.console.cost.ledger.domain.BucketDay;
import com.iaf.console.cost.ledger.domain.CostCell;
import com.iaf.console.cost.ledger.domain.CostFilter;
import com.iaf.console.cost.ledger.domain.Granularity;
import com.iaf.console.cost.ledger.ports.outbound.DayStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The closed days in MongoDB: one document per account, day and bucket, with the bucket's
 * dimensions and the day's amount beside the record, so a report is one aggregation that matches
 * on the account and the days and groups by the period and a dimension in the database.
 */
public final class MongoDays implements DayStorePort, MongoIndexed {

    static final String COLLECTION = "cost_days";

    private final Mongo mongo;

    public MongoDays(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("accountId", "day"), new IndexOptions().name("account_day"))));
    }

    @Override
    public Mono<Void> replaceDay(String accountId, LocalDate day, List<BucketDay> rows) {
        Bson ofDay = Filters.and(Filters.eq("accountId", accountId), Filters.eq("day", day.toString()));
        List<Document> docs = rows.stream().map(this::document).toList();
        Mono<Void> delete = Mono.from(mongo.collection(COLLECTION).deleteMany(ofDay)).then();
        return docs.isEmpty() ? delete : delete.then(Mono.from(mongo.collection(COLLECTION).insertMany(docs)).then());
    }

    @Override
    public Flux<BucketDay> listDay(AccountScope scope, LocalDate day) {
        return Flux.from(mongo.collection(COLLECTION).find(Filters.and(Filters.eq("accountId", scope.accountId()), Filters.eq("day", day.toString())))
                .sort(Sorts.ascending("_id"))).map(d -> mongo.read(d, BucketDay.class));
    }

    @Override
    public Flux<CostCell> sum(AccountScope scope, LocalDate from, LocalDate to, Granularity granularity, String dimension, CostFilter filter) {
        List<Bson> match = new ArrayList<>(List.of(Filters.eq("accountId", scope.accountId()), Filters.gte("day", from.toString()),
                Filters.lt("day", to.toString()), Filters.ne("amount", 0L)));
        if (filter.service() != null) {
            match.add(Filters.eq("service", filter.service()));
        }
        if (filter.type() != null) {
            match.add(Filters.eq("type", filter.type()));
        }
        if (filter.region() != null) {
            match.add(Filters.eq("region", filter.region()));
        }
        filter.tags().forEach((k, v) -> match.add(Filters.eq("tags." + k, v)));
        Object period = granularity == Granularity.DAY ? "$day"
                : new Document("$concat", List.of(new Document("$substrCP", List.of("$day", 0, 7)), "-01"));
        Object key = dimension == null ? CostCell.NONE : new Document("$ifNull", List.of(field(dimension), CostCell.NONE));
        return Flux.from(mongo.collection(COLLECTION).aggregate(List.of(
                Aggregates.match(Filters.and(match)),
                Aggregates.group(new Document("period", period).append("key", key), Accumulators.sum("amount", "$amount")),
                Aggregates.sort(Sorts.ascending("_id.period", "_id.key")))))
                .map(d -> {
                    Document id = d.get("_id", Document.class);
                    String k = id.getString("key");
                    return new CostCell(LocalDate.parse(id.getString("period")), k == null || k.isEmpty() ? CostCell.NONE : k,
                            ((Number) d.get("amount")).longValue());
                });
    }

    @Override
    public Mono<Long> deleteBefore(String accountId, LocalDate day) {
        return Mono.from(mongo.collection(COLLECTION).deleteMany(Filters.and(Filters.eq("accountId", accountId), Filters.lt("day", day.toString()))))
                .map(r -> r.getDeletedCount());
    }

    private static String field(String dimension) {
        return switch (dimension) {
            case "service" -> "$service";
            case "type" -> "$type";
            case "region" -> "$region";
            case "account" -> "$accountId";
            default -> {
                if (!dimension.startsWith("tag:")) {
                    throw new IllegalArgumentException("no dimension " + dimension);
                }
                yield "$tags." + dimension.substring(4);
            }
        };
    }

    private Document document(BucketDay row) {
        return new Document("_id", row.id()).append("accountId", row.accountId()).append("day", row.day().toString())
                .append("service", row.bucket().service()).append("type", row.bucket().type()).append("region", row.bucket().region())
                .append("tags", new Document(new java.util.LinkedHashMap<String, Object>(row.bucket().tags()))).append("amount", row.amountMicros())
                .append(Mongo.DOC, mongo.doc(row));
    }
}

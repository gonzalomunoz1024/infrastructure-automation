package com.iaf.platform.providers.adapters.outbound;

import java.time.Instant;
import java.util.Optional;

import org.bson.Document;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.providers.ports.outbound.ProviderAlertStatePort;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

/**
 * The alert evaluator's state under the {@code mongo} profile: one document of
 * {@code provider_alert_state} ({@code _id} {@value #EVALUATOR}), so a restarted instance picks up
 * where the last evaluation stopped. The exact instant is kept in epoch nanoseconds
 * ({@value #AT_NS}) beside a date for people reading the data ({@value #AT}), and it only moves
 * forward: a write is one {@code $max} of both on that document, atomic, so of two instances
 * evaluating at once the later instant stays, and the date, being the same instant to the
 * millisecond, never disagrees with the nanoseconds.
 */
@Component
@Primary
@Profile("mongo")
public class MongoProviderAlertState implements ProviderAlertStatePort {

    static final String COLLECTION = "provider_alert_state";
    static final String EVALUATOR = "evaluator";
    static final String AT = "lastEvaluatedAt";
    static final String AT_NS = "lastEvaluatedAtNs";
    private static final long NANOS = 1_000_000_000L;

    private final Mongo mongo;

    public MongoProviderAlertState(Mongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Instant> lastEvaluatedAt() {
        Document d = mongo.collection(COLLECTION).find(Filters.eq("_id", EVALUATOR)).first();
        if (d == null || !(d.get(AT_NS) instanceof Number ns)) {
            return Optional.empty();
        }
        long n = ns.longValue();
        return Optional.of(Instant.ofEpochSecond(Math.floorDiv(n, NANOS), Math.floorMod(n, NANOS)));
    }

    @Override
    public void lastEvaluatedAt(Instant at) {
        if (at == null) {
            return;
        }
        mongo.collection(COLLECTION).updateOne(Filters.eq("_id", EVALUATOR),
                Updates.combine(Updates.max(AT_NS, Mongo.nanos(at)), Updates.max(AT, Mongo.date(at))), new UpdateOptions().upsert(true));
    }
}

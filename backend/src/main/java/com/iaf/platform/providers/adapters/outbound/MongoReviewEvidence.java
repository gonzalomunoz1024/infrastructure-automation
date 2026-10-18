package com.iaf.platform.providers.adapters.outbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.providers.domain.ReviewEvidence;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;
import com.iaf.platform.providers.ports.outbound.ReviewEvidencePort;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

/**
 * The review evidence of the {@code mongo} profile: one document of {@code review_evidence} per
 * connection test or Try it call, by provider name, so what the wizard proved survives a restart
 * and every instance sees it at once. The bounds are the in-memory store's: the last
 * {@value InMemoryReviewEvidence#MAX_CONNECTIONS} connection tests and the last
 * {@value InMemoryReviewEvidence#MAX_TRIES} tries of a provider, with a count of the older ones
 * dropped to keep to that, and a response over {@value InMemoryReviewEvidence#MAX_RESPONSE_BYTES}
 * bytes is kept without its body. A document holds endpoints, credential references and
 * responses, never a credential value.
 *
 * <p>The entries of a provider and kind are numbered 1, 2, 3... in the order they were recorded
 * ({@code seq}, unique with the provider and the kind). A write inserts its entry under the number
 * after the newest; when another instance took that number first, the unique index refuses the
 * insert and the write takes the next one. The numbers have no gaps, so what a read returns is the
 * newest entries within the bound, and the count of the dropped ones is the number of the oldest
 * of those minus one: no transaction and no count of its own to keep in step. Deleting the entries
 * that fell out of the bound is tidying up only; a write that stops before it leaves entries no
 * read returns and the count already includes.
 */
@Component
@Primary
@Profile("mongo")
public class MongoReviewEvidence implements ReviewEvidencePort {

    static final String COLLECTION = "review_evidence";
    static final String CONNECTION = "connection";
    static final String TRY = "try";
    /** Losing the race for a number this often is no longer contention but a fault. */
    static final int MAX_ATTEMPTS = 100;

    private final Mongo mongo;

    public MongoReviewEvidence(Mongo mongo) {
        this.mongo = mongo;
        mongo.index(COLLECTION, Indexes.ascending("provider", "kind", "seq"), new IndexOptions().unique(true));
    }

    @Override
    public void recordConnection(String provider, ConnectionTest test) {
        if (provider == null || test == null) {
            return;
        }
        record(provider, CONNECTION, test.at(), mongo.doc(test), InMemoryReviewEvidence.MAX_CONNECTIONS);
    }

    @Override
    public void recordTry(String provider, ActionTry tryResult) {
        if (provider == null || tryResult == null) {
            return;
        }
        ActionTry kept = InMemoryReviewEvidence.bounded(tryResult);
        record(provider, TRY, kept.at(), mongo.doc(kept), InMemoryReviewEvidence.MAX_TRIES);
    }

    @Override
    public ReviewEvidence evidence(String provider) {
        if (provider == null) {
            return ReviewEvidence.NONE;
        }
        Window<ConnectionTest> connections = window(provider, CONNECTION, InMemoryReviewEvidence.MAX_CONNECTIONS, ConnectionTest.class);
        Window<ActionTry> tries = window(provider, TRY, InMemoryReviewEvidence.MAX_TRIES, ActionTry.class);
        return new ReviewEvidence(connections.entries(), tries.entries(), connections.dropped(), tries.dropped());
    }

    /** Appends an entry, then deletes the provider's entries of that kind that fell out of the last {@code keep}. */
    private void record(String provider, String kind, Instant at, Document doc, int keep) {
        long seq = append(provider, kind, at, doc);
        mongo.collection(COLLECTION).deleteMany(Filters.and(of(provider, kind), Filters.lte("seq", seq - keep)));
    }

    /** Inserts the entry under the number after the newest of its provider and kind, and returns that number. */
    private long append(String provider, String kind, Instant at, Document doc) {
        for (int attempt = 1;; attempt++) {
            long seq = newest(provider, kind) + 1;
            try {
                mongo.collection(COLLECTION).insertOne(new Document("provider", provider).append("kind", kind).append("seq", seq)
                        .append("at", Mongo.date(at)).append(Mongo.DOC, doc));
                return seq;
            } catch (MongoException e) {
                if (!Mongo.duplicateKey(e)) {
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("no free number for a " + kind + " entry of provider " + provider + " after " + attempt + " attempts", e);
                }
            }
        }
    }

    private long newest(String provider, String kind) {
        Document d = mongo.collection(COLLECTION).find(of(provider, kind)).sort(Sorts.descending("seq")).projection(Projections.include("seq")).first();
        return d == null ? 0 : ((Number) d.get("seq")).longValue();
    }

    private record Window<T>(List<T> entries, int dropped) {
    }

    /** The newest {@code keep} entries of a provider and kind, oldest first, and how many older ones the bound dropped. */
    private <T> Window<T> window(String provider, String kind, int keep, Class<T> type) {
        List<Document> newestFirst = mongo.collection(COLLECTION).find(of(provider, kind)).sort(Sorts.descending("seq")).limit(keep)
                .into(new ArrayList<>());
        if (newestFirst.isEmpty()) {
            return new Window<>(List.of(), 0);
        }
        List<T> entries = new ArrayList<>(newestFirst.size());
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            entries.add(mongo.read(newestFirst.get(i), type));
        }
        long dropped = ((Number) newestFirst.get(newestFirst.size() - 1).get("seq")).longValue() - 1;
        return new Window<>(entries, (int) Math.max(0, Math.min(Integer.MAX_VALUE, dropped)));
    }

    private static Bson of(String provider, String kind) {
        return Filters.and(Filters.eq("provider", provider), Filters.eq("kind", kind));
    }
}

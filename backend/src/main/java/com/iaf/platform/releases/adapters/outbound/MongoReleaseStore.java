package com.iaf.platform.releases.adapters.outbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.domain.ReleaseStatus;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;

/**
 * The releases of the {@code mongo} profile: one document per release in {@code releases}, keyed
 * by its id, newest first (started at, then id), the active ones (canary or paused) found by their
 * {@code status} field. A workflow release is found by its {@code workflow} field, a provider
 * release by its {@code provider} field; {@link #list(String)} with no workflow returns both kinds,
 * as in memory. A save replaces the release whole, the last write winning, as in memory.
 */
@Component
@Primary
@Profile("mongo")
public class MongoReleaseStore implements ReleaseStore {

    static final String COLLECTION = "releases";

    private static final Comparator<Release> NEWEST_FIRST = Comparator
            .comparing((Release r) -> r.startedAt() == null ? Instant.EPOCH : r.startedAt()).reversed()
            .thenComparing(Release::id);
    private static final Bson ACTIVE = Filters.in("status",
            Arrays.stream(ReleaseStatus.values()).filter(ReleaseStatus::active).map(ReleaseStatus::json).toList());

    private final Mongo mongo;

    public MongoReleaseStore(Mongo mongo) {
        this.mongo = mongo;
        mongo.index(COLLECTION, Indexes.ascending("workflow"));
        mongo.index(COLLECTION, Indexes.ascending("provider"));
        mongo.index(COLLECTION, Indexes.ascending("status"));
    }

    @Override
    public Release save(Release release) {
        Document d = new Document("_id", release.id())
                .append("workflow", release.workflow())
                .append("provider", release.provider())
                .append("status", release.status() == null ? null : release.status().json())
                .append("startedAt", Mongo.date(release.startedAt()))
                .append("startedNs", Mongo.nanos(release.startedAt()))
                .append(Mongo.DOC, mongo.doc(release));
        mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", release.id()), d, new ReplaceOptions().upsert(true));
        return release;
    }

    @Override
    public Optional<Release> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(mongo.collection(COLLECTION).find(Filters.eq("_id", id)).first()).map(this::read);
    }

    @Override
    public List<Release> list(String workflow) {
        if (workflow == null || workflow.isBlank()) {
            return newestFirst(Filters.empty());
        }
        return newestFirst(Filters.eq("workflow", workflow));
    }

    @Override
    public List<Release> listProvider(String provider) {
        return provider == null ? List.of() : newestFirst(Filters.eq("provider", provider));
    }

    @Override
    public List<Release> active() {
        return newestFirst(ACTIVE);
    }

    @Override
    public Optional<Release> active(String workflow) {
        return workflow == null ? Optional.empty() : newestFirst(Filters.and(Filters.eq("workflow", workflow), ACTIVE)).stream().findFirst();
    }

    @Override
    public Optional<Release> activeProvider(String provider) {
        return provider == null ? Optional.empty() : newestFirst(Filters.and(Filters.eq("provider", provider), ACTIVE)).stream().findFirst();
    }

    /** The releases the filter matches, in the in-memory store's order (an undated release counts as started at the epoch). */
    private List<Release> newestFirst(Bson filter) {
        List<Release> out = new ArrayList<>();
        mongo.collection(COLLECTION).find(filter).forEach(d -> out.add(read(d)));
        out.sort(NEWEST_FIRST);
        return out;
    }

    private Release read(Document stored) {
        return mongo.read(stored, Release.class);
    }
}

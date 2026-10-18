package com.iaf.platform.runs.adapters.outbound;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.domain.Hashes;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobEvent;
import com.iaf.platform.runs.ports.outbound.ProjectionStore;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

import lombok.extern.slf4j.Slf4j;

/**
 * The runs index of the {@code mongo} profile. A job is a document of {@code runs_jobs} keyed by
 * its id, whose doc is the job without its definition snapshot: the snapshot is stored once per
 * distinct definition in {@code runs_definitions} (keyed by the SHA-256 of its JSON) and kept in a
 * small cache, since many jobs run the same version. Approvals are documents of
 * {@code runs_approvals} keyed by their id; the event history is {@code runs_events}, one document
 * per event keyed by {@code {jobId, seq}}, read in {@code seq} order through a unique index on the
 * two, so a job never has two events with one number. Lists are filtered, sorted and limited in
 * MongoDB, newest first to the nanosecond; the short lists (children, approvals) are sorted as the
 * in-memory store sorts them. A job whose snapshot document is missing reads without its
 * definition (with a warning) rather than failing every listing it is in.
 *
 * <p>A save writes the snapshot, then the approvals, then the job, each on its own: a save cut off
 * in between leaves at most a snapshot no job names yet or approvals newer than their job's
 * document, never a job naming a snapshot or an approval that cannot be found. The projection
 * saves the whole job again on its next event (a retried event folds to the same job), which
 * brings the two together.
 */
@Slf4j
@Component
@Primary
@Profile("mongo")
public class MongoProjectionStore implements ProjectionStore {

    static final String JOBS = "runs_jobs";
    static final String APPROVALS = "runs_approvals";
    static final String EVENTS = "runs_events";
    static final String DEFINITIONS = "runs_definitions";

    private static final int CACHED_DEFINITIONS = 256;
    private static final Bson NEWEST_FIRST = Sorts.orderBy(Sorts.descending("startedNs"), Sorts.ascending("_id"));

    private final Mongo mongo;
    /** Definition snapshots by hash; immutable, so a cached one is never stale. */
    private final Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, WorkflowDefinition> eldest) {
            return size() > CACHED_DEFINITIONS;
        }
    };

    public MongoProjectionStore(Mongo mongo) {
        this.mongo = mongo;
        // a job not started yet sorts last: its null startedNs orders below every number, so last when descending
        mongo.index(JOBS, Indexes.compoundIndex(Indexes.descending("startedNs"), Indexes.ascending("_id")));
        mongo.index(JOBS, Indexes.compoundIndex(Indexes.ascending("workflow"), Indexes.descending("startedNs"), Indexes.ascending("_id")));
        mongo.index(JOBS, Indexes.compoundIndex(Indexes.ascending("parentId"), Indexes.descending("startedNs"), Indexes.ascending("_id")));
        mongo.index(EVENTS, Indexes.ascending("_id.jobId", "_id.seq"), new IndexOptions().unique(true));
    }

    @Override
    public Optional<Job> job(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.collection(JOBS).find(Filters.eq("_id", id)).first()).map(this::read);
    }

    @Override
    public void save(Job job) {
        String hash = job.definition() == null ? null : keep(job.definition());
        for (Approval a : job.approvals()) {
            Document approval = new Document("_id", a.id()).append(Mongo.DOC, mongo.doc(a));
            mongo.collection(APPROVALS).replaceOne(Filters.eq("_id", a.id()), approval, new ReplaceOptions().upsert(true));
        }
        Document d = new Document("_id", job.id())
                .append("workflow", job.workflow())
                .append("status", job.status() == null ? null : job.status().json())
                .append("parentId", job.parentId())
                .append("startedNs", Mongo.nanos(job.startedAt()))
                .append("definitionHash", hash)
                .append(Mongo.DOC, mongo.doc(job.withDefinition(null)));
        mongo.collection(JOBS).replaceOne(Filters.eq("_id", job.id()), d, new ReplaceOptions().upsert(true));
    }

    @Override
    public List<Job> jobs(JobQuery query) {
        JobQuery q = query == null ? JobQuery.ALL : query;
        List<Bson> where = new ArrayList<>();
        if (q.workflow() != null) {
            where.add(Filters.eq("workflow", q.workflow()));
        }
        if (q.status() != null) {
            where.add(Filters.eq("status", q.status().json()));
        }
        if (q.parentId() != null) {
            where.add(Filters.eq("parentId", q.parentId()));
        }
        if (q.topLevelOnly()) {
            where.add(Filters.eq("parentId", null));
        }
        FindIterable<Document> found = mongo.collection(JOBS).find(where.isEmpty() ? Filters.empty() : Filters.and(where)).sort(NEWEST_FIRST);
        if (q.limit() > 0) {
            found = found.limit(q.limit());
        }
        List<Job> out = new ArrayList<>();
        found.forEach(d -> out.add(read(d)));
        return out;
    }

    @Override
    public List<Job> children(String parentId) {
        if (parentId == null) {
            return List.of();
        }
        List<Job> out = new ArrayList<>();
        mongo.collection(JOBS).find(Filters.eq("parentId", parentId)).forEach(d -> out.add(read(d)));
        out.sort(InMemoryProjectionStore.ROWS_THEN_OLDEST);
        return out;
    }

    @Override
    public Optional<Approval> approval(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.collection(APPROVALS).find(Filters.eq("_id", id)).first()).map(d -> mongo.read(d, Approval.class));
    }

    @Override
    public List<Approval> approvals() {
        List<Approval> out = new ArrayList<>();
        mongo.collection(APPROVALS).find().forEach(d -> out.add(mongo.read(d, Approval.class)));
        out.sort(InMemoryProjectionStore.APPROVALS_NEWEST_FIRST);
        return out;
    }

    /** Inserts the event; one whose job already has an event with its {@code seq} is refused by the unique index (a duplicate key error). */
    @Override
    public void append(JobEvent event) {
        mongo.collection(EVENTS).insertOne(new Document("_id", new Document("jobId", event.jobId()).append("seq", event.seq()))
                .append(Mongo.DOC, mongo.doc(event)));
    }

    @Override
    public List<JobEvent> history(String jobId) {
        if (jobId == null) {
            return List.of();
        }
        List<JobEvent> out = new ArrayList<>();
        mongo.collection(EVENTS).find(Filters.eq("_id.jobId", jobId)).sort(Sorts.ascending("_id.seq"))
                .forEach(d -> out.add(mongo.read(d, JobEvent.class)));
        return out;
    }

    @Override
    public long lastSeq(String jobId) {
        if (jobId == null) {
            return 0;
        }
        Document last = mongo.collection(EVENTS).find(Filters.eq("_id.jobId", jobId)).sort(Sorts.descending("_id.seq"))
                .projection(Projections.include("_id")).limit(1).first();
        return last == null ? 0 : ((Number) last.get("_id", Document.class).get("seq")).longValue();
    }

    private Job read(Document stored) {
        Job job = mongo.read(stored, Job.class);
        String hash = stored.getString("definitionHash");
        if (hash == null) {
            return job;
        }
        WorkflowDefinition d = definition(hash);
        if (d == null) {
            log.warn("job {} ran definition {}, which the runs index does not have: it is read without its snapshot", job.id(), hash);
            return job;
        }
        return job.withDefinition(d);
    }

    /** The snapshot with this hash, from the cache or its document; null when no document has it. */
    private WorkflowDefinition definition(String hash) {
        synchronized (definitions) {
            WorkflowDefinition cached = definitions.get(hash);
            if (cached != null) {
                return cached;
            }
        }
        Document stored = mongo.collection(DEFINITIONS).find(Filters.eq("_id", hash)).first();
        if (stored == null) {
            return null;
        }
        WorkflowDefinition d = mongo.read(stored, WorkflowDefinition.class);
        synchronized (definitions) {
            definitions.put(hash, d);
        }
        return d;
    }

    /** Store a snapshot once and return its hash; the same object (jobs share their version's) is hashed once. */
    private String keep(WorkflowDefinition d) {
        synchronized (definitions) {
            for (Map.Entry<String, WorkflowDefinition> e : definitions.entrySet()) {
                if (e.getValue() == d) {
                    return e.getKey();
                }
            }
        }
        String hash = Hashes.sha256(json(d));
        boolean known;
        synchronized (definitions) {
            known = definitions.containsKey(hash);
        }
        if (!known) {
            try {
                mongo.collection(DEFINITIONS).insertOne(new Document("_id", hash).append(Mongo.DOC, mongo.doc(d)));
            } catch (MongoException e) {
                if (!Mongo.duplicateKey(e)) {
                    throw e;
                }
                // stored already, by this instance before a restart or by another one
            }
        }
        synchronized (definitions) {
            definitions.put(hash, d);
        }
        return hash;
    }

    private static String json(WorkflowDefinition d) {
        try {
            return Mongo.mapper().writeValueAsString(d);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot hash definition " + d.name() + ": " + e.getOriginalMessage(), e);
        }
    }
}

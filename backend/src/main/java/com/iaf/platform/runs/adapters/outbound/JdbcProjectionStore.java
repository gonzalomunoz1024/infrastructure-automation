package com.iaf.platform.runs.adapters.outbound;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobEvent;
import com.iaf.platform.runs.ports.outbound.ProjectionStore;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import lombok.extern.slf4j.Slf4j;

/**
 * The runs index of the {@code postgres} profile. A job is a row of {@code runs_jobs} whose doc is
 * the job without its definition snapshot: the snapshot is stored once per distinct definition in
 * {@code runs_definitions} (keyed by the SHA-256 of its JSON) and kept in a small cache, since
 * many jobs run the same version. Approvals are rows of {@code runs_approvals}, the event history
 * rows of {@code runs_events}. Lists are filtered and limited in SQL, newest first to the
 * nanosecond; the short lists (children, approvals) are sorted as the in-memory store sorts them.
 * A job whose snapshot row is missing reads without its definition (with a warning) rather than
 * failing every listing it is in.
 */
@Slf4j
@Component
@Primary
@Profile("postgres")
public class JdbcProjectionStore implements ProjectionStore {

    private static final int CACHED_DEFINITIONS = 256;
    private static final String JOB_COLUMNS = "SELECT definition_hash, doc FROM runs_jobs";

    private final Jdbc jdbc;
    /** Definition snapshots by hash; immutable, so a cached one is never stale. */
    private final Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, WorkflowDefinition> eldest) {
            return size() > CACHED_DEFINITIONS;
        }
    };

    public JdbcProjectionStore(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Job> job(String id) {
        return id == null ? Optional.empty() : jdbc.first(JOB_COLUMNS + " WHERE id = ?", (rs, i) -> read(rs.getString(1), rs.getString(2)), id);
    }

    @Override
    public void save(Job job) {
        String hash = job.definition() == null ? null : keep(job.definition());
        String doc = jdbc.json(job.withDefinition(null));
        String status = job.status() == null ? null : job.status().json();
        String kind = job.kind() == null ? null : job.kind().json();
        jdbc.inTransaction(() -> {
            jdbc.upsert("UPDATE runs_jobs SET workflow = ?, version = ?, status = ?, kind = ?, parent_id = ?, row_index = ?, started_at = ?, started_ns = ?,"
                    + " definition_hash = ?, doc = ? WHERE id = ?",
                    new Object[] {job.workflow(), job.version(), status, kind, job.parentId(), job.row(), Jdbc.time(job.startedAt()),
                            Jdbc.nanos(job.startedAt()), hash, doc, job.id()},
                    "INSERT INTO runs_jobs (id, workflow, version, status, kind, parent_id, row_index, started_at, started_ns, definition_hash, doc)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    new Object[] {job.id(), job.workflow(), job.version(), status, kind, job.parentId(), job.row(), Jdbc.time(job.startedAt()),
                            Jdbc.nanos(job.startedAt()), hash, doc});
            for (Approval a : job.approvals()) {
                String approval = jdbc.json(a);
                String state = a.status() == null ? null : a.status().json();
                jdbc.upsert("UPDATE runs_approvals SET job_id = ?, status = ?, requested_at = ?, requested_ns = ?, doc = ? WHERE id = ?",
                        new Object[] {job.id(), state, Jdbc.time(a.requestedAt()), Jdbc.nanos(a.requestedAt()), approval, a.id()},
                        "INSERT INTO runs_approvals (id, job_id, status, requested_at, requested_ns, doc) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                        new Object[] {a.id(), job.id(), state, Jdbc.time(a.requestedAt()), Jdbc.nanos(a.requestedAt()), approval});
            }
        });
    }

    @Override
    public List<Job> jobs(JobQuery query) {
        JobQuery q = query == null ? JobQuery.ALL : query;
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (q.workflow() != null) {
            where.append(" AND workflow = ?");
            args.add(q.workflow());
        }
        if (q.status() != null) {
            where.append(" AND status = ?");
            args.add(q.status().json());
        }
        if (q.parentId() != null) {
            where.append(" AND parent_id = ?");
            args.add(q.parentId());
        }
        if (q.topLevelOnly()) {
            where.append(" AND parent_id IS NULL");
        }
        StringBuilder sql = new StringBuilder(JOB_COLUMNS);
        if (!where.isEmpty()) {
            sql.append(" WHERE").append(where.substring(4));
        }
        sql.append(" ORDER BY started_ns DESC NULLS LAST, id");
        if (q.limit() > 0) {
            sql.append(" LIMIT ?");
            args.add(q.limit());
        }
        return jdbc.query(sql.toString(), (rs, i) -> read(rs.getString(1), rs.getString(2)), args.toArray());
    }

    @Override
    public List<Job> children(String parentId) {
        if (parentId == null) {
            return List.of();
        }
        List<Job> out = new ArrayList<>(jdbc.query(JOB_COLUMNS + " WHERE parent_id = ?", (rs, i) -> read(rs.getString(1), rs.getString(2)), parentId));
        out.sort(InMemoryProjectionStore.ROWS_THEN_OLDEST);
        return out;
    }

    @Override
    public Optional<Approval> approval(String id) {
        return id == null ? Optional.empty()
                : jdbc.first("SELECT doc FROM runs_approvals WHERE id = ?", (rs, i) -> jdbc.read(rs.getString(1), Approval.class), id);
    }

    @Override
    public List<Approval> approvals() {
        List<Approval> out = new ArrayList<>(jdbc.query("SELECT doc FROM runs_approvals", (rs, i) -> jdbc.read(rs.getString(1), Approval.class)));
        out.sort(InMemoryProjectionStore.APPROVALS_NEWEST_FIRST);
        return out;
    }

    @Override
    public void append(JobEvent event) {
        jdbc.update("INSERT INTO runs_events (job_id, seq, at, type, doc) VALUES (?, ?, ?, ?, ?)",
                event.jobId(), event.seq(), Jdbc.time(event.at()), event.type(), jdbc.json(event));
    }

    @Override
    public List<JobEvent> history(String jobId) {
        return jobId == null ? List.of()
                : jdbc.query("SELECT doc FROM runs_events WHERE job_id = ? ORDER BY seq", (rs, i) -> jdbc.read(rs.getString(1), JobEvent.class), jobId);
    }

    @Override
    public long lastSeq(String jobId) {
        return jobId == null ? 0 : jdbc.first("SELECT MAX(seq) FROM runs_events WHERE job_id = ?", (rs, i) -> rs.getLong(1), jobId).orElse(0L);
    }

    private Job read(String hash, String doc) {
        Job job = jdbc.read(doc, Job.class);
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

    /** The snapshot with this hash, from the cache or its row; null when no row has it. */
    private WorkflowDefinition definition(String hash) {
        synchronized (definitions) {
            WorkflowDefinition cached = definitions.get(hash);
            if (cached != null) {
                return cached;
            }
        }
        WorkflowDefinition d = jdbc.first("SELECT doc FROM runs_definitions WHERE hash = ?", (rs, i) -> jdbc.read(rs.getString(1), WorkflowDefinition.class), hash)
                .orElse(null);
        if (d != null) {
            synchronized (definitions) {
                definitions.put(hash, d);
            }
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
        String json = jdbc.json(d);
        String hash = Jdbc.sha256(json);
        boolean known;
        synchronized (definitions) {
            known = definitions.containsKey(hash);
        }
        if (!known) {
            jdbc.insertIfAbsent("INSERT INTO runs_definitions (hash, doc) VALUES (?, ?) ON CONFLICT DO NOTHING", hash, json);
        }
        synchronized (definitions) {
            definitions.put(hash, d);
        }
        return hash;
    }
}

package com.iaf.platform.runs.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.Batch.BatchRow;
import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobEvent;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep;
import com.iaf.platform.runs.domain.JobStep.StepError;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.domain.StepFolding;
import com.iaf.platform.runs.ports.outbound.ProjectionStore;
import com.iaf.platform.runs.ports.outbound.ProjectionStore.JobQuery;
import com.iaf.platform.workflows.domain.StepType;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * The read model of the runs slice: every job, approval and per-job event history, kept in the
 * {@link ProjectionStore} (in memory, or in PostgreSQL with the {@code postgres} profile) and
 * updated from the {@code job.*} and {@code batch.*} events. Command handlers
 * {@link #put} a job (with its definition snapshot and the requester's identity, which no event
 * carries) before publishing {@code job.started}; everything after that is folded from events,
 * so the Temporal engine, the in-memory engine and the demo seed all feed the same model. The
 * live fan-out (one replaying sink per running job) stays in memory and ends with the job.
 */
@Slf4j
@Component
public class JobProjection {

    private static final int REPLAY_BUFFER = 2048;

    private final EventBus bus;
    private final ProjectionStore store;
    private final Object lock = new Object();
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();

    /** One live job's event numbering and its sink (a bounded replay closes the gap between history and live). */
    private static final class Stream {
        final Sinks.Many<JobEvent> sink = Sinks.many().replay().limit(REPLAY_BUFFER);
        long seq;

        Stream(long seq) {
            this.seq = seq;
        }
    }

    public JobProjection(EventBus bus, ProjectionStore store) {
        this.bus = bus;
        this.store = store;
    }

    @PostConstruct
    public void register() {
        bus.register(this::on);
    }

    // ---------------------------------------------------------------- writes from the handlers

    /** Store (or replace) a job record. */
    public void put(Job job) {
        synchronized (lock) {
            store.save(job);
        }
    }

    public Optional<Job> update(String id, UnaryOperator<Job> change) {
        synchronized (lock) {
            Job j = store.job(id).orElse(null);
            if (j == null) {
                return Optional.empty();
            }
            Job next = change.apply(j);
            store.save(next);
            return Optional.of(next);
        }
    }

    // ---------------------------------------------------------------- reads

    public Optional<Job> get(String id) {
        return store.job(id);
    }

    public boolean has(String id) {
        return id != null && store.job(id).isPresent();
    }

    /** Every job, newest first. */
    public List<Job> all() {
        return store.jobs(JobQuery.ALL);
    }

    /** The matching jobs, newest first, at most {@code query.limit()} of them. */
    public List<Job> jobs(JobQuery query) {
        return store.jobs(query);
    }

    /** The children of a job (rows of a batch, children of run steps), rows first, oldest first. */
    public List<Job> children(String parentId) {
        return store.children(parentId);
    }

    public Optional<Approval> approval(String id) {
        return store.approval(id);
    }

    /** Every approval, newest first. */
    public List<Approval> approvals() {
        return store.approvals();
    }

    public List<JobEvent> history(String jobId) {
        synchronized (lock) {
            return store.history(jobId);
        }
    }

    /** History first, then live events until the job ends; an ended job replays its history only. */
    public Flux<JobEvent> events(String jobId) {
        return Flux.defer(() -> {
            synchronized (lock) {
                List<JobEvent> hist = store.history(jobId);
                Job job = store.job(jobId).orElse(null);
                boolean ended = job != null && job.status() != null && job.status().terminal();
                Flux<JobEvent> history = Flux.fromIterable(hist);
                if (ended) {
                    return history;
                }
                Stream s = stream(jobId);
                long last = s.seq;
                Flux<JobEvent> live = s.sink.asFlux().filter(e -> e.seq() > last).takeUntil(JobEvent::terminal);
                return Flux.concat(history, live);
            }
        });
    }

    // ---------------------------------------------------------------- event folding

    void on(DomainEvent e) {
        if (!JobEvents.AGGREGATE.equals(e.aggregate()) || e.aggregateId() == null) {
            return;
        }
        if (!e.type().startsWith("job.") && !e.type().startsWith("batch.")) {
            return;
        }
        synchronized (lock) {
            Job job = null;
            try {
                job = store.job(e.aggregateId()).orElse(null);
                Job next = apply(job, e);
                if (next != null) {
                    store.save(next);
                    job = next;
                    if (next.parentId() != null && next.row() != null) {
                        updateParentRow(next, e);
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("job projection could not apply {} to {}: {}", e.type(), e.aggregateId(), ex.toString());
            }
            try {
                record(e, job != null && job.status() != null && job.status().terminal());
            } catch (RuntimeException ex) {
                log.warn("job projection could not keep {} of {}: {}", e.type(), e.aggregateId(), ex.toString());
            }
        }
    }

    /** Number the event, keep it, hand it to the live subscribers; an ended job's sink goes (its history stays in the store). */
    private void record(DomainEvent e, boolean ended) {
        Stream s = stream(e.aggregateId());
        ObjectNode data = e.data() != null && e.data().isObject() ? ((ObjectNode) e.data()).deepCopy() : JobViews.MAPPER.createObjectNode();
        data.put("event", e.type());
        JobEvent je = new JobEvent(s.seq + 1, e.at(), e.aggregateId(), JobEvent.typeOf(e.type()), data);
        store.append(je);
        s.seq = je.seq();
        s.sink.tryEmitNext(je);
        if (ended) {
            streams.remove(e.aggregateId());
        }
    }

    /** The live state of a job's stream, numbering on from its stored history. */
    private Stream stream(String jobId) {
        return streams.computeIfAbsent(jobId, id -> new Stream(store.lastSeq(id)));
    }

    private Job apply(Job job, DomainEvent e) {
        JsonNode d = e.data() == null ? JobViews.MAPPER.createObjectNode() : e.data();
        return switch (e.type()) {
            case "job.started" -> started(job, e, d);
            case "job.step" -> job == null ? null : step(job, e, d);
            case "job.approval-requested" -> job == null ? null : approvalRequested(job, e, d);
            case "job.approval-decided" -> job == null ? null : approvalDecided(job, e, d);
            case "job.attention" -> job == null ? null : job.toBuilder().attention(attention(e, d)).status(JobStatus.NEEDS_ATTENTION).build();
            case "job.attention-resolved" -> job == null ? null : job.toBuilder().attention(null).status(JobStatus.RUNNING).build();
            case "job.finished" -> job == null ? null : finished(job, e, d);
            case "job.cancelled" -> job == null ? null : cancelled(job, e);
            case "batch.wave" -> job == null ? null : wave(job, e, d);
            case "job.change-opened", "job.change-closed" -> job == null || !d.path("change").isObject() ? job
                    : job.withChange(new ChangeTicket(text(d.path("change"), "id"), text(d.path("change"), "url"), text(d.path("change"), "state")));
            default -> null;
        };
    }

    private Job started(Job job, DomainEvent e, JsonNode d) {
        if (job != null) {
            Job.JobBuilder b = job.toBuilder();
            if (job.status() == null) {
                b.status(JobStatus.QUEUED);
            }
            if (job.startedAt() == null) {
                b.startedAt(e.at());
            }
            return b.build();
        }
        String requester = text(d, "requester");
        Job.Kind kind = d.hasNonNull("kind") ? Job.Kind.of(d.get("kind").asText()) : Job.Kind.SINGLE;
        return Job.builder()
                .id(e.aggregateId())
                .workflow(text(d, "workflow"))
                .title(text(d, "title"))
                .version(text(d, "version"))
                .status(JobStatus.QUEUED)
                .requester(requester == null ? Identity.SYSTEM : new Identity(requester, requester, null, List.of(), List.of(), false, Identity.Kind.USER))
                .channel(text(d, "channel"))
                .startedAt(e.at())
                .parentId(text(d, "parentId"))
                .kind(kind)
                .input(d.get("input"))
                .traceId(text(d, "traceId"))
                .environment(text(d, "environment"))
                .row(d.hasNonNull("row") ? d.get("row").asInt() : null)
                .preview(convert(d.get("preview"), Preview.class))
                .policy(convert(d.get("policy"), PolicyDecision.class))
                .change(d.path("change").isObject() ? new ChangeTicket(text(d.path("change"), "id"), text(d.path("change"), "url"), text(d.path("change"), "state")) : null)
                .build();
    }

    private Job step(Job job, DomainEvent e, JsonNode d) {
        StepStatus status = d.hasNonNull("status") ? StepStatus.of(d.get("status").asText()) : StepStatus.RUNNING;
        StepType type = d.hasNonNull("type") ? safeType(d.get("type").asText()) : null;
        StepError error = d.path("error").isObject()
                ? new StepError(text(d.path("error"), "class"), text(d.path("error"), "message"), d.path("error").path("retryable").asBoolean(false))
                : null;
        List<JobStep> rows = new ArrayList<>(job.steps());
        StepFolding.apply(rows, text(d, "step"), text(d, "name"), type, status, d.path("attempt").asInt(0), d.get("input"), d.get("output"),
                error, e.at(), d.hasNonNull("durationMs") ? d.get("durationMs").asLong() : null, d.hasNonNull("items") ? d.get("items").asInt() : null);
        Job.JobBuilder b = job.toBuilder().steps(rows);
        if (status == StepStatus.RUNNING && (job.status() == null || job.status().active())) {
            b.status(JobStatus.RUNNING).attention(null);
        } else if (status == StepStatus.WAITING && type == StepType.APPROVAL) {
            b.status(JobStatus.WAITING_APPROVAL);
        } else if (status == StepStatus.FAILED && error != null && "expired".equals(error.errorClass())) {
            b.approvals(job.approvals().stream()
                    .map(a -> a.pending() && a.step() != null && a.step().equals(text(d, "step")) ? a.withStatus(Approval.Status.EXPIRED).withDecidedAt(e.at()) : a)
                    .toList());
        }
        return b.build();
    }

    private Job approvalRequested(Job job, DomainEvent e, JsonNode d) {
        Approval a = Approval.builder()
                .id(text(d, "approvalId"))
                .jobId(job.id())
                .workflow(text(d, "workflow") == null ? job.workflow() : text(d, "workflow"))
                .step(text(d, "step"))
                .group(text(d, "group"))
                .message(text(d, "message"))
                .requestedAt(e.at())
                .timeout(text(d, "timeout"))
                .status(Approval.Status.PENDING)
                .preview(d.has("preview") ? convert(d.get("preview"), Preview.class) : job.preview())
                .build();
        List<Approval> list = new ArrayList<>(job.approvals().stream().filter(x -> !x.id().equals(a.id())).toList());
        list.add(a);
        return job.toBuilder().approvals(list).status(JobStatus.WAITING_APPROVAL).build();
    }

    private Job approvalDecided(Job job, DomainEvent e, JsonNode d) {
        String id = text(d, "approvalId");
        String decision = text(d, "decision");
        Approval.Status status = "approved".equals(decision) ? Approval.Status.APPROVED
                : "rejected".equals(decision) ? Approval.Status.REJECTED : Approval.Status.EXPIRED;
        List<Approval> list = job.approvals().stream()
                .map(a -> a.id().equals(id) ? a.withStatus(status).withDecidedBy(text(d, "by")).withDecidedAt(e.at()).withComment(text(d, "comment")) : a)
                .toList();
        Job.JobBuilder b = job.toBuilder().approvals(list);
        if (status == Approval.Status.APPROVED && job.status() == JobStatus.WAITING_APPROVAL) {
            b.status(JobStatus.RUNNING);
        }
        return b.build();
    }

    private static Attention attention(DomainEvent e, JsonNode d) {
        List<String> options = new ArrayList<>();
        if (d.path("options").isArray()) {
            d.get("options").forEach(o -> options.add(o.asText()));
        }
        StepError error = d.path("error").isObject()
                ? new StepError(text(d.path("error"), "class"), text(d.path("error"), "message"), d.path("error").path("retryable").asBoolean(false))
                : null;
        Instant since = d.hasNonNull("since") ? Instant.parse(d.get("since").asText()) : e.at();
        return new Attention(text(d, "step"), error, options.isEmpty() ? Attention.OPTIONS : options, since,
                text(d, "timeout") == null ? Attention.DEFAULT_TIMEOUT : text(d, "timeout"));
    }

    private Job finished(Job job, DomainEvent e, JsonNode d) {
        JobStatus status = d.hasNonNull("status") ? JobStatus.of(d.get("status").asText()) : JobStatus.SUCCEEDED;
        long duration = d.hasNonNull("durationMs") ? d.get("durationMs").asLong()
                : job.startedAt() == null ? 0 : Math.max(0, e.at().toEpochMilli() - job.startedAt().toEpochMilli());
        Job.JobBuilder b = job.toBuilder()
                .status(status)
                .endedAt(e.at())
                .durationMs(duration)
                .errorClass(text(d, "errorClass"))
                .failedStep(text(d, "failedStep"))
                .output(d.get("output"))
                .attention(null)
                .approvals(expirePending(job.approvals(), e.at()));
        if (job.batch() != null) {
            Batch batch = job.batch();
            if (d.hasNonNull("message") && batch.stoppedReason() == null && status != JobStatus.SUCCEEDED) {
                batch = batch.withStoppedReason(d.get("message").asText());
            }
            b.batch(batch).progress(progressOf(batch));
        }
        return b.build();
    }

    private Job cancelled(Job job, DomainEvent e) {
        long duration = job.startedAt() == null ? 0 : Math.max(0, e.at().toEpochMilli() - job.startedAt().toEpochMilli());
        Job.JobBuilder b = job.toBuilder()
                .status(JobStatus.CANCELLED)
                .endedAt(e.at())
                .durationMs(duration)
                .attention(null)
                .approvals(expirePending(job.approvals(), e.at()));
        if (job.batch() != null) {
            List<BatchRow> rows = job.batch().rows().stream()
                    .map(r -> "pending".equals(r.status()) ? r.withStatus("skipped") : r)
                    .toList();
            List<Wave> waves = job.batch().waves().stream()
                    .map(w -> "pending".equals(w.status()) ? w.withStatus("skipped") : "running".equals(w.status()) ? w.withStatus("stopped").withEndedAt(e.at()) : w)
                    .toList();
            Batch batch = job.batch().withRows(rows).withWaves(waves);
            b.batch(batch).progress(progressOf(batch));
        }
        return b.build();
    }

    private Job wave(Job job, DomainEvent e, JsonNode d) {
        if (job.batch() == null) {
            return job;
        }
        int index = d.path("wave").asInt(0);
        String status = text(d, "status") == null ? "pending" : text(d, "status");
        Batch batch = job.batch();
        List<Wave> waves = new ArrayList<>(batch.waves() == null ? List.of() : batch.waves());
        Wave existing = batch.wave(index);
        Wave next = existing == null
                ? new Wave(index, d.path("percent").asInt(100), d.path("size").asInt(0), status, null, null)
                : existing.withStatus(status);
        if ("running".equals(status)) {
            next = next.withStartedAt(e.at());
        } else if (!"pending".equals(status)) {
            next = next.withEndedAt(e.at());
        }
        if (existing == null) {
            waves.add(next);
        } else {
            waves.set(waves.indexOf(existing), next);
        }
        List<BatchRow> rows = batch.rows();
        if (d.path("rows").isArray()) {
            List<Integer> indexes = new ArrayList<>();
            d.get("rows").forEach(n -> indexes.add(n.asInt()));
            rows = rows.stream().map(r -> {
                if (!indexes.contains(r.index())) {
                    return r;
                }
                if (("skipped".equals(status) || "stopped".equals(status)) && ("pending".equals(r.status()) || r.status() == null)) {
                    return r.withStatus("skipped").withWave(index);
                }
                if ("pending".equals(status)) {
                    return r.withWave(index).withStatus("pending").withJobId(null).withError(null);
                }
                return r.wave() == null ? r.withWave(index) : r;
            }).toList();
        }
        Batch nb = batch.withWaves(waves).withRows(rows);
        if (text(d, "reason") != null) {
            nb = nb.withStoppedReason(text(d, "reason"));
        }
        Job.JobBuilder b = job.toBuilder().batch(nb).progress(progressOf(nb));
        if ("running".equals(status) && (job.status() == JobStatus.QUEUED || job.status() == JobStatus.WAITING_APPROVAL)) {
            b.status(JobStatus.RUNNING);
        }
        return b.build();
    }

    /** A row's child job started, ended or was cancelled: reflect it on the batch. */
    private void updateParentRow(Job child, DomainEvent e) {
        Job parent = store.job(child.parentId()).orElse(null);
        if (parent == null || parent.batch() == null) {
            return;
        }
        String rowStatus = switch (e.type()) {
            case "job.started" -> "running";
            case "job.finished" -> child.status() == JobStatus.SUCCEEDED ? "succeeded" : child.status() == JobStatus.CANCELLED ? "cancelled" : "failed";
            case "job.cancelled" -> "cancelled";
            default -> null;
        };
        if (rowStatus == null) {
            return;
        }
        List<BatchRow> rows = parent.batch().rows().stream().map(r -> {
            if (r.index() != child.row()) {
                return r;
            }
            BatchRow next = r.withJobId(child.id()).withStatus(rowStatus);
            if ("failed".equals(rowStatus)) {
                String err = child.errorClass() == null ? child.status().json() : child.errorClass() + (child.failedStep() == null ? "" : " at " + child.failedStep());
                next = next.withError(err);
            } else if ("running".equals(rowStatus)) {
                next = next.withError(null);
            }
            return next;
        }).toList();
        Batch batch = parent.batch().withRows(rows);
        store.save(parent.toBuilder().batch(batch).progress(progressOf(batch)).build());
    }

    public static Job.Progress progressOf(Batch batch) {
        return new Job.Progress((int) batch.done(), batch.total(), (int) batch.count("failed"));
    }

    private static List<Approval> expirePending(List<Approval> approvals, Instant at) {
        return approvals.stream().map(a -> a.pending() ? a.withStatus(Approval.Status.EXPIRED).withDecidedAt(at) : a).toList();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static StepType safeType(String key) {
        try {
            return StepType.of(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> T convert(JsonNode n, Class<T> type) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return JobViews.MAPPER.treeToValue(n, type);
        } catch (Exception e) {
            return null;
        }
    }
}

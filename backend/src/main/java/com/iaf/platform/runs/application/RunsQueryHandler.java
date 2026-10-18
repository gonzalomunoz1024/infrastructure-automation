package com.iaf.platform.runs.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.redaction.SecretValues;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep;
import com.iaf.platform.runs.domain.JsonDiff;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.runs.ports.outbound.ProjectionStore.JobQuery;
import com.iaf.platform.runs.ports.outbound.WorkflowExecutionPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The read side of the runs slice, served from the {@link JobProjection}; its store may block, so
 * reads run on bounded-elastic threads. Every view of a job's input, step input and output and
 * events is redacted on the way out (secret-looking fields and secrets the platform resolved
 * become {@code ***}); the projection itself keeps what ran, so a rerun replays the real values.
 * A job's view also links to it in the engine's own tools ({@link WorkflowExecutionPort#links}).
 */
@Service
@Primary
public class RunsQueryHandler implements RunsQueries {

    public static final int DEFAULT_LIMIT = 50;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final JobProjection projection;
    private final WorkflowExecutionPort engine;
    private final SecretValues secrets;

    public RunsQueryHandler(JobProjection projection, WorkflowExecutionPort engine) {
        this(projection, engine, new SecretValues());
    }

    @Autowired
    public RunsQueryHandler(JobProjection projection, WorkflowExecutionPort engine, SecretValues secrets) {
        this.projection = projection;
        this.engine = engine;
        this.secrets = secrets;
    }

    @Override
    public Flux<JsonNode> jobs(String workflow, String status, String parent, Integer limit) {
        JobStatus wanted = status == null || status.isBlank() ? null : JobStatus.of(status);
        int max = limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
        boolean topLevel = parent == null || parent.isBlank();
        JobQuery query = new JobQuery(workflow == null || workflow.isBlank() ? null : workflow, wanted, topLevel ? null : parent, topLevel, max);
        return Flux.defer(() -> Flux.fromIterable(projection.jobs(query)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(JobViews::summary);
    }

    @Override
    public Mono<JsonNode> job(String id) {
        return Mono.defer(() -> Mono.justOrEmpty(projection.get(id)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(j -> {
                    ObjectNode detail = JobViews.detail(j, projection.children(j.id()));
                    Map<String, String> links = engine.links(j.id());
                    if (links != null && !links.isEmpty()) {
                        ObjectNode l = detail.putObject("links");
                        links.forEach(l::put);
                    }
                    return secrets.redact(detail);
                });
    }

    @Override
    public Flux<JsonNode> events(String id) {
        return Flux.defer(() -> projection.has(id)
                ? projection.events(id).map(e -> secrets.redact(JobViews.event(e)))
                : Flux.<JsonNode>error(new NotFoundException("job", id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<JsonNode> approvals(String status) {
        return Flux.defer(() -> Flux.fromIterable(projection.approvals()))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(a -> status == null || status.isBlank() || (a.status() != null && a.status().json().equals(status.toLowerCase())))
                .map(JobViews::approval);
    }

    @Override
    public Mono<JsonNode> approval(String id) {
        return Mono.defer(() -> Mono.justOrEmpty(projection.approval(id))).subscribeOn(Schedulers.boundedElastic()).map(JobViews::approval);
    }

    @Override
    public Mono<JsonNode> replay(String id, String compareTo) {
        return Mono.defer(() -> Mono.justOrEmpty(projection.get(id))).subscribeOn(Schedulers.boundedElastic()).map(job -> {
            Optional<Job> compared = compareTo != null && !compareTo.isBlank() ? projection.get(compareTo) : previousOf(job);
            if (compareTo != null && !compareTo.isBlank() && compared.isEmpty()) {
                throw new NotFoundException("job", compareTo);
            }
            ObjectNode out = F.objectNode();
            out.put("jobId", job.id());
            out.put("workflow", job.workflow());
            out.put("version", job.version());
            out.put("status", job.status() == null ? null : job.status().json());
            compared.ifPresent(c -> {
                out.put("comparedTo", c.id());
                out.put("comparedVersion", c.version());
                out.put("comparedStatus", c.status() == null ? null : c.status().json());
                ArrayNode inputDiff = out.putArray("inputDiff");
                JsonDiff.diff(c.input(), job.input(), "input").forEach(d -> inputDiff.add(diffEntry(d)));
            });
            ArrayNode steps = out.putArray("steps");
            for (JobStep s : job.steps()) {
                ObjectNode row = JobViews.step(s);
                compared.ifPresent(c -> {
                    JobStep other = c.step(s.id());
                    ArrayNode diff = row.putArray("diff");
                    if (other == null) {
                        diff.add(diffEntry(new JsonDiff.Entry("status", null, F.textNode(s.status() == null ? "" : s.status().json()))));
                    } else {
                        if (other.status() != s.status()) {
                            diff.add(diffEntry(new JsonDiff.Entry("status", other.status() == null ? null : F.textNode(other.status().json()),
                                    s.status() == null ? null : F.textNode(s.status().json()))));
                        }
                        JsonDiff.diff(other.input(), s.input(), "input").forEach(d -> diff.add(diffEntry(d)));
                        JsonDiff.diff(other.output(), s.output(), "output").forEach(d -> diff.add(diffEntry(d)));
                        if (other.error() != null || s.error() != null) {
                            String before = other.error() == null ? null : other.error().errorClass();
                            String after = s.error() == null ? null : s.error().errorClass();
                            if (before == null ? after != null : !before.equals(after)) {
                                diff.add(diffEntry(new JsonDiff.Entry("error.class", before == null ? null : F.textNode(before), after == null ? null : F.textNode(after))));
                            }
                        }
                    }
                });
                steps.add(row);
            }
            return secrets.redact(out);
        });
    }

    /**
     * The previous successful run of the same workflow (same kind: top-level jobs compare with
     * top-level jobs). A failed or cancelled run in between is skipped: the point of the diff is
     * what changed since the workflow last worked.
     */
    private Optional<Job> previousOf(Job job) {
        Instant at = job.startedAt() == null ? Instant.EPOCH : job.startedAt();
        return projection.jobs(new JobQuery(job.workflow(), null, null, false, 0)).stream()
                .filter(j -> !j.id().equals(job.id()))
                .filter(j -> j.kind() == job.kind())
                .filter(j -> j.status() == JobStatus.SUCCEEDED)
                .filter(j -> (j.parentId() == null) == (job.parentId() == null))
                .filter(j -> j.startedAt() != null && j.startedAt().isBefore(at))
                .findFirst();
    }

    private static ObjectNode diffEntry(JsonDiff.Entry d) {
        ObjectNode o = F.objectNode();
        o.put("field", d.field());
        o.set("before", d.before() == null ? F.nullNode() : d.before());
        o.set("after", d.after() == null ? F.nullNode() : d.after());
        return o;
    }

    @Override
    public Mono<List<JobOutcome>> outcomes(String workflow, String version, Instant since) {
        return Mono.fromSupplier(() -> {
            List<JobOutcome> out = new ArrayList<>();
            for (Job j : projection.jobs(new JobQuery(workflow == null || workflow.isBlank() ? null : workflow, null, null, false, 0))) {
                if (j.status() == null || !j.status().terminal() || j.isBatch()) {
                    continue;
                }
                if (version != null && !version.isBlank() && !version.equals(j.version())) {
                    continue;
                }
                if (since != null && (j.startedAt() == null || j.startedAt().isBefore(since))) {
                    continue;
                }
                out.add(new JobOutcome(j.id(), j.workflow(), j.version(), j.status().json(), j.startedAt(), j.endedAt(),
                        j.durationMs() == null ? 0 : j.durationMs(), j.errorClass(), j.failedStep(), j.input()));
            }
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<JsonNode>> steps(String id) {
        return Mono.defer(() -> Mono.justOrEmpty(projection.get(id)))
                .map(j -> j.steps().stream().map(s -> secrets.redact(JobViews.step(s))).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }
}

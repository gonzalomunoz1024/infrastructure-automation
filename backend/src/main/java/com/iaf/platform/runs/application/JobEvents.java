package com.iaf.platform.runs.application;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.StepEvent;

import lombok.RequiredArgsConstructor;

/**
 * Every event the runs slice publishes, with the data shapes the audit, releases and providers
 * slices listen for. The aggregate is always {@code job}; the aggregate id the job's.
 */
@Component
@RequiredArgsConstructor
public class JobEvents {

    public static final String AGGREGATE = "job";
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final EventBus events;

    /** {@code job.started}: {jobId, workflow, version, requester, channel, input, parentId, kind, preview, policy, change} (+ title, traceId, row, rerunOf). */
    public DomainEvent started(Job job) {
        return started(job, null);
    }

    public DomainEvent started(Job job, String rerunOf) {
        return started(job, rerunOf, null);
    }

    /** {@code job.started} stamped at {@code at} (the seed shifts history into the past). */
    public DomainEvent started(Job job, String rerunOf, Instant at) {
        ObjectNode d = base(job.id());
        d.put("workflow", job.workflow());
        d.put("version", job.version());
        d.put("requester", job.requesterId());
        d.put("channel", job.channel());
        d.set("input", job.input() == null ? F.objectNode() : job.input());
        if (job.parentId() != null) {
            d.put("parentId", job.parentId());
        }
        d.put("kind", (job.kind() == null ? Job.Kind.SINGLE : job.kind()).json());
        if (job.preview() != null) {
            d.set("preview", JobViews.preview(job.preview()));
        }
        if (job.policy() != null) {
            d.set("policy", JobViews.MAPPER.valueToTree(job.policy()));
        }
        if (job.change() != null) {
            d.set("change", JobViews.change(job.change()));
        }
        d.put("title", job.title());
        d.put("traceId", job.traceId());
        String contentHash = contentHash(job);
        if (contentHash != null) {
            d.put("contentHash", contentHash);
        }
        if (job.row() != null) {
            d.put("row", job.row());
        }
        if (job.environment() != null) {
            d.put("environment", job.environment());
        }
        if (rerunOf != null) {
            d.put("rerunOf", rerunOf);
        }
        if (job.resume() != null && job.resume().fromStep() != null) {
            d.put("resumedFrom", job.resume().fromStep());
        }
        if (job.batch() != null) {
            d.put("rows", job.batch().total());
        }
        return publish("job.started", job.id(), job.requesterId(), d, at);
    }

    /** {@code job.step}: {jobId, step, name, type, status, attempt, input, output, error, durationMs, items}. */
    public DomainEvent step(String jobId, StepEvent e) {
        ObjectNode d = base(jobId);
        d.put("step", e.id());
        d.put("name", e.name());
        d.put("type", e.step().type().key());
        d.put("status", e.status().json());
        d.put("attempt", e.attempt());
        if (e.input() != null && !e.input().isNull()) {
            d.set("input", e.input());
        }
        if (e.output() != null && !e.output().isNull()) {
            d.set("output", e.output());
        }
        if (e.error() != null) {
            ObjectNode err = d.putObject("error");
            err.put("class", e.error().errorClass());
            err.put("message", e.error().message());
            err.put("retryable", e.error().retryable());
        }
        if (e.durationMs() != null) {
            d.put("durationMs", e.durationMs());
        }
        if (e.items() != null) {
            d.put("items", e.items());
        }
        if (e.replayed()) {
            d.put("replayed", true);
        }
        return publish("job.step", jobId, Identity.SYSTEM.id(), d, e.at());
    }

    /** {@code job.approval-requested}: {approvalId, jobId, workflow, step, group, message, timeout}. */
    public DomainEvent approvalRequested(Approval a) {
        ObjectNode d = base(a.jobId());
        d.put("approvalId", a.id());
        d.put("workflow", a.workflow());
        d.put("step", a.step());
        d.put("group", a.group());
        d.put("message", a.message());
        d.put("timeout", a.timeout());
        if (a.preview() != null) {
            d.set("preview", JobViews.preview(a.preview()));
        }
        return publish("job.approval-requested", a.jobId(), Identity.SYSTEM.id(), d, a.requestedAt());
    }

    /** {@code job.approval-decided}: {approvalId, jobId, decision, by, comment}. */
    public DomainEvent approvalDecided(Approval a, ApprovalDecision decision) {
        ObjectNode d = base(a.jobId());
        d.put("approvalId", a.id());
        d.put("step", a.step());
        d.put("decision", decision.decision());
        d.put("by", decision.by());
        if (decision.comment() != null) {
            d.put("comment", decision.comment());
        }
        return publish("job.approval-decided", a.jobId(), decision.by(), d, decision.at());
    }

    /** {@code job.approval-escalated}: {approvalId, jobId, step, to}. */
    public DomainEvent approvalEscalated(String jobId, String approvalId, String step, String to) {
        return approvalEscalated(jobId, approvalId, step, to, null);
    }

    public DomainEvent approvalEscalated(String jobId, String approvalId, String step, String to, Instant at) {
        ObjectNode d = base(jobId);
        d.put("approvalId", approvalId);
        d.put("step", step);
        d.put("to", to);
        return publish("job.approval-escalated", jobId, Identity.SYSTEM.id(), d, at);
    }

    /** {@code job.attention}: {jobId, step, error, options, since, timeout}. */
    public DomainEvent attention(String jobId, Attention a) {
        ObjectNode d = base(jobId);
        d.setAll(JobViews.attention(a));
        return publish("job.attention", jobId, Identity.SYSTEM.id(), d, a.since());
    }

    /** {@code job.attention-resolved}: {jobId, step, action, by}. */
    public DomainEvent attentionResolved(String jobId, String step, String action, String by) {
        return attentionResolved(jobId, step, action, by, null);
    }

    public DomainEvent attentionResolved(String jobId, String step, String action, String by, Instant at) {
        return attentionResolved(jobId, step, action, by, null, at);
    }

    /** {@code job.attention-resolved} with the edited input of an edit-input decision. */
    public DomainEvent attentionResolved(String jobId, String step, String action, String by, JsonNode input, Instant at) {
        ObjectNode d = base(jobId);
        d.put("step", step);
        d.put("action", action);
        d.put("by", by);
        if (input != null && !input.isNull() && !input.isMissingNode()) {
            d.set("input", input);
        }
        return publish("job.attention-resolved", jobId, by, d, at);
    }

    /** {@code job.changed}: {jobId, step, provider, action, effect, target}: a provider call that created, changed or deleted something. */
    public DomainEvent changed(String jobId, String step, String provider, String action, String effect, String target) {
        ObjectNode d = base(jobId);
        d.put("step", step);
        d.put("provider", provider);
        d.put("action", action);
        d.put("effect", effect);
        if (target != null) {
            d.put("target", target);
        }
        return publish("job.changed", jobId, Identity.SYSTEM.id(), d);
    }

    /** {@code job.finished}: {jobId, workflow, version, status, durationMs, errorClass, failedStep, output}. */
    public DomainEvent finished(String jobId, String workflow, String version, JobStatus status, long durationMs, String errorClass,
            String failedStep, JsonNode output, String message, Instant at) {
        ObjectNode d = base(jobId);
        d.put("workflow", workflow);
        d.put("version", version);
        d.put("status", status.json());
        d.put("durationMs", durationMs);
        if (errorClass != null) {
            d.put("errorClass", errorClass);
        }
        if (failedStep != null) {
            d.put("failedStep", failedStep);
        }
        if (output != null && !output.isNull()) {
            d.set("output", output);
        }
        if (message != null) {
            d.put("message", message);
        }
        return publish("job.finished", jobId, Identity.SYSTEM.id(), d, at);
    }

    /** {@code job.cancelled}: {jobId, by}. */
    public DomainEvent cancelled(String jobId, String by) {
        return cancelled(jobId, by, null);
    }

    public DomainEvent cancelled(String jobId, String by, Instant at) {
        ObjectNode d = base(jobId);
        d.put("by", by);
        return publish("job.cancelled", jobId, by, d, at);
    }

    /** {@code job.log}: {jobId, message}. */
    public DomainEvent log(String jobId, String message) {
        return log(jobId, message, null);
    }

    public DomainEvent log(String jobId, String message, Instant at) {
        ObjectNode d = base(jobId);
        d.put("message", message);
        return publish("job.log", jobId, Identity.SYSTEM.id(), d, at);
    }

    /** {@code batch.wave}: {jobId, wave, percent, size, status, reason, rows}: {@code rows} are the row indexes the wave holds. */
    public DomainEvent wave(String jobId, Wave w, String reason, java.util.List<Integer> rows) {
        return wave(jobId, w, reason, rows, null);
    }

    public DomainEvent wave(String jobId, Wave w, String reason, java.util.List<Integer> rows, Instant at) {
        ObjectNode d = base(jobId);
        d.put("wave", w.index());
        d.put("percent", w.percent());
        d.put("size", w.size());
        d.put("status", w.status());
        if (reason != null) {
            d.put("reason", reason);
        }
        if (rows != null) {
            var arr = d.putArray("rows");
            rows.forEach(arr::add);
        }
        return publish("batch.wave", jobId, Identity.SYSTEM.id(), d, at);
    }

    /** {@code job.change-opened}: {jobId, change}. */
    public DomainEvent changeOpened(String jobId, ChangeTicket change, String by) {
        return changeOpened(jobId, change, by, null);
    }

    public DomainEvent changeOpened(String jobId, ChangeTicket change, String by, Instant at) {
        ObjectNode d = base(jobId);
        d.set("change", JobViews.change(change));
        return publish("job.change-opened", jobId, by, d, at);
    }

    /** {@code job.change-closed}: {jobId, change}. */
    public DomainEvent changeClosed(String jobId, ChangeTicket change) {
        return changeClosed(jobId, change, null);
    }

    public DomainEvent changeClosed(String jobId, ChangeTicket change, Instant at) {
        ObjectNode d = base(jobId);
        d.set("change", JobViews.change(change));
        return publish("job.change-closed", jobId, Identity.SYSTEM.id(), d, at);
    }

    public DomainEvent publish(String type, String jobId, String actor, ObjectNode data) {
        return events.publish(type, AGGREGATE, jobId, actor == null ? Identity.SYSTEM.id() : actor, data);
    }

    /** Publish with an explicit timestamp (step events carry the walker's clock; the seed shifts history). */
    public DomainEvent publish(String type, String jobId, String actor, ObjectNode data, Instant at) {
        if (at == null) {
            return publish(type, jobId, actor, data);
        }
        DomainEvent e = new DomainEvent(com.iaf.platform.common.domain.Ids.next("evt"), at, type, AGGREGATE, jobId,
                actor == null ? Identity.SYSTEM.id() : actor, data);
        events.publish(e);
        return e;
    }

    private static ObjectNode base(String jobId) {
        ObjectNode d = F.objectNode();
        d.put("jobId", jobId);
        return d;
    }

    /** The content hash of the version the job runs (its definition snapshot), so evidence names exactly what ran. */
    private static String contentHash(Job job) {
        if (job.definition() == null) {
            return null;
        }
        try {
            return com.iaf.platform.workflows.domain.WorkflowYaml.contentHash(job.definition());
        } catch (RuntimeException e) {
            return null;
        }
    }
}

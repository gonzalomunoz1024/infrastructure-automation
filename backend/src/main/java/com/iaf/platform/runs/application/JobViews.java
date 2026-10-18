package com.iaf.platform.runs.application;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobEvent;
import com.iaf.platform.runs.domain.JobStep;
import com.iaf.platform.runs.domain.Preview;

/** The API shapes of docs/api.md (JobSummary, JobDetail, JobStep, Approval, JobEvent, Batch, Attention), built by hand so nothing engine-side leaks. */
public final class JobViews {

    /** ISO dates, no nulls, no timestamps: the same rules as the application's mapper. */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private JobViews() {
    }

    public static ObjectNode summary(Job j) {
        ObjectNode o = F.objectNode();
        o.put("id", j.id());
        o.put("workflow", j.workflow());
        o.put("title", j.title() == null ? j.workflow() : j.title());
        o.put("version", j.version());
        o.put("status", j.status() == null ? null : j.status().json());
        o.put("requester", j.requesterId());
        o.put("channel", j.channel());
        put(o, "startedAt", j.startedAt());
        put(o, "endedAt", j.endedAt());
        if (j.durationMs() != null) {
            o.put("durationMs", j.durationMs());
        }
        if (j.parentId() != null) {
            o.put("parentId", j.parentId());
        }
        o.put("kind", (j.kind() == null ? Job.Kind.SINGLE : j.kind()).json());
        if (j.progress() != null) {
            ObjectNode p = o.putObject("progress");
            p.put("done", j.progress().done());
            p.put("total", j.progress().total());
            p.put("failed", j.progress().failed());
        }
        if (j.row() != null) {
            o.put("row", j.row());
        }
        if (j.errorClass() != null) {
            o.put("errorClass", j.errorClass());
        }
        if (j.failedStep() != null) {
            o.put("failedStep", j.failedStep());
        }
        return o;
    }

    public static ObjectNode detail(Job j, List<Job> children) {
        ObjectNode o = summary(j);
        o.set("input", j.input() == null ? F.objectNode() : j.input());
        if (j.output() != null && !j.output().isNull()) {
            o.set("output", j.output());
        }
        ArrayNode steps = o.putArray("steps");
        j.steps().forEach(s -> steps.add(step(s)));
        ArrayNode approvals = o.putArray("approvals");
        j.approvals().forEach(a -> approvals.add(approval(a)));
        if (j.preview() != null) {
            o.set("preview", preview(j.preview()));
        }
        if (j.change() != null) {
            o.set("change", change(j.change()));
        }
        if (children != null && !children.isEmpty()) {
            ArrayNode c = o.putArray("children");
            children.forEach(ch -> c.add(summary(ch)));
        }
        if (j.batch() != null) {
            o.set("batch", batch(j.batch()));
        }
        if (j.attention() != null) {
            o.set("attention", attention(j.attention()));
        }
        o.put("traceId", j.traceId());
        if (j.policy() != null) {
            o.set("policy", MAPPER.valueToTree(j.policy()));
        }
        if (j.environment() != null) {
            o.put("environment", j.environment());
        }
        if (j.resume() != null && j.resume().fromStep() != null) {
            o.put("resumedFrom", j.resume().fromStep());
        }
        return o;
    }

    public static ObjectNode step(JobStep s) {
        ObjectNode o = F.objectNode();
        o.put("id", s.id());
        o.put("name", s.name() == null ? s.id() : s.name());
        o.put("type", s.type() == null ? null : s.type().key());
        o.put("status", s.status() == null ? null : s.status().json());
        o.put("attempt", s.attempt());
        put(o, "startedAt", s.startedAt());
        put(o, "endedAt", s.endedAt());
        if (s.durationMs() != null) {
            o.put("durationMs", s.durationMs());
        }
        if (s.input() != null && !s.input().isNull()) {
            o.set("input", s.input());
        }
        if (s.output() != null && !s.output().isNull()) {
            o.set("output", s.output());
        }
        if (s.error() != null) {
            ObjectNode e = o.putObject("error");
            e.put("class", s.error().errorClass());
            e.put("message", s.error().message());
            e.put("retryable", s.error().retryable());
        }
        if (s.items() != null) {
            o.put("items", s.items());
        }
        return o;
    }

    public static ObjectNode approval(Approval a) {
        ObjectNode o = F.objectNode();
        o.put("id", a.id());
        o.put("jobId", a.jobId());
        o.put("workflow", a.workflow());
        o.put("step", a.step());
        o.put("group", a.group());
        if (a.escalatedTo() != null) {
            o.put("escalatedTo", a.escalatedTo());
        }
        o.put("message", a.message());
        put(o, "requestedAt", a.requestedAt());
        o.put("timeout", a.timeout());
        o.put("status", a.status() == null ? null : a.status().json());
        if (a.decidedBy() != null) {
            o.put("decidedBy", a.decidedBy());
        }
        put(o, "decidedAt", a.decidedAt());
        if (a.comment() != null) {
            o.put("comment", a.comment());
        }
        if (a.preview() != null) {
            o.set("preview", preview(a.preview()));
        }
        return o;
    }

    public static ObjectNode event(JobEvent e) {
        ObjectNode o = F.objectNode();
        o.put("seq", e.seq());
        put(o, "at", e.at());
        o.put("jobId", e.jobId());
        o.put("type", e.type());
        o.set("data", e.data() == null ? F.objectNode() : e.data());
        return o;
    }

    public static ObjectNode attention(Attention a) {
        ObjectNode o = F.objectNode();
        o.put("step", a.step());
        if (a.error() != null) {
            ObjectNode e = o.putObject("error");
            e.put("class", a.error().errorClass());
            e.put("message", a.error().message());
            e.put("retryable", a.error().retryable());
        }
        ArrayNode opts = o.putArray("options");
        (a.options() == null ? Attention.OPTIONS : a.options()).forEach(opts::add);
        put(o, "since", a.since());
        o.put("timeout", a.timeout());
        return o;
    }

    public static ObjectNode batch(Batch b) {
        ObjectNode o = F.objectNode();
        ArrayNode rows = o.putArray("rows");
        if (b.rows() != null) {
            for (Batch.BatchRow r : b.rows()) {
                ObjectNode ro = rows.addObject();
                ro.put("index", r.index());
                ro.set("input", r.input() == null ? F.objectNode() : r.input());
                if (r.jobId() != null) {
                    ro.put("jobId", r.jobId());
                }
                ro.put("status", r.status());
                if (r.error() != null) {
                    ro.put("error", r.error());
                }
                if (r.wave() != null) {
                    ro.put("wave", r.wave());
                }
            }
        }
        ArrayNode waves = o.putArray("waves");
        if (b.waves() != null) {
            for (Batch.Wave w : b.waves()) {
                ObjectNode wo = waves.addObject();
                wo.put("index", w.index());
                wo.put("percent", w.percent());
                wo.put("size", w.size());
                wo.put("status", w.status());
                put(wo, "startedAt", w.startedAt());
                put(wo, "endedAt", w.endedAt());
            }
        }
        if (b.rollout() != null) {
            ObjectNode r = o.putObject("rollout");
            ArrayNode ws = r.putArray("waves");
            b.rollout().waves().forEach(ws::add);
            r.put("maxParallel", b.rollout().maxParallel());
            r.put("stopIfFailedPercent", b.rollout().stopIfFailedPercent());
        }
        if (b.stoppedReason() != null) {
            o.put("stoppedReason", b.stoppedReason());
        }
        return o;
    }

    public static ObjectNode change(ChangeTicket c) {
        ObjectNode o = F.objectNode();
        o.put("id", c.id());
        o.put("url", c.url());
        o.put("state", c.state());
        if (c.evidenceUrl() != null) {
            o.put("evidenceUrl", c.evidenceUrl());
        }
        return o;
    }

    public static JsonNode preview(Preview p) {
        return MAPPER.valueToTree(p);
    }

    private static void put(ObjectNode o, String field, Instant at) {
        if (at != null) {
            o.put(field, at.toString());
        }
    }
}

package com.iaf.console.fake.runs.domain;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** An approval a job waits for, and how it was decided. */
public final class ApprovalRecord {

    private final String id;
    private final String jobId;
    private final String workflow;
    private final String step;
    private final String group;
    private final String message;
    private final Instant requestedAt;
    private final String timeout;
    private final JsonNode preview;
    private String status = "pending";
    private String decidedBy;
    private Instant decidedAt;
    private String comment;
    private String escalatedTo;

    public ApprovalRecord(String id, String jobId, String workflow, String step, String group, String message, Instant requestedAt, String timeout,
            JsonNode preview) {
        this.id = id;
        this.jobId = jobId;
        this.workflow = workflow;
        this.step = step;
        this.group = group;
        this.message = message;
        this.requestedAt = requestedAt;
        this.timeout = timeout;
        this.preview = preview;
    }

    public String id() {
        return id;
    }

    public String jobId() {
        return jobId;
    }

    public String workflow() {
        return workflow;
    }

    public String step() {
        return step;
    }

    public String group() {
        return group;
    }

    public String message() {
        return message;
    }

    public String timeout() {
        return timeout;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public JsonNode preview() {
        return preview;
    }

    public synchronized String status() {
        return status;
    }

    public synchronized boolean pending() {
        return "pending".equals(status);
    }

    public synchronized void decide(String decision, String by, Instant at, String why) {
        this.status = decision;
        this.decidedBy = by;
        this.decidedAt = at;
        this.comment = why;
    }

    public synchronized void escalate(String to) {
        this.escalatedTo = to;
    }

    public synchronized ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("id", id);
        out.put("jobId", jobId);
        out.put("workflow", workflow);
        out.put("step", step);
        out.put("group", group);
        out.put("message", message);
        out.put("requestedAt", requestedAt.toString());
        out.put("timeout", timeout);
        out.put("status", status);
        if (decidedBy != null) {
            out.put("decidedBy", decidedBy);
        }
        if (decidedAt != null) {
            out.put("decidedAt", decidedAt.toString());
        }
        if (comment != null) {
            out.put("comment", comment);
        }
        if (preview != null && !preview.isNull()) {
            out.set("preview", preview);
        }
        if (escalatedTo != null) {
            out.put("escalatedTo", escalatedTo);
        }
        return out;
    }
}

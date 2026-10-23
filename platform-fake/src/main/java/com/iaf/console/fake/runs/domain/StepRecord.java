package com.iaf.console.fake.runs.domain;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One step of a job as the job page shows it, with the alias and variables it set so a rerun can restore them. */
public final class StepRecord {

    private final String id;
    private final String name;
    private final String type;
    private String status = "pending";
    private int attempt;
    private Instant startedAt;
    private Instant endedAt;
    private Long durationMs;
    private JsonNode input;
    private JsonNode output;
    private StepError error;
    private Integer items;
    private String alias;
    private ObjectNode vars;
    private boolean replayed;

    public StepRecord(String id, String name, String type) {
        this.id = id;
        this.name = name == null ? id : name;
        this.type = type;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String type() {
        return type;
    }

    public String status() {
        return status;
    }

    public int attempt() {
        return attempt;
    }

    public JsonNode input() {
        return input;
    }

    public JsonNode output() {
        return output;
    }

    public StepError error() {
        return error;
    }

    public Integer items() {
        return items;
    }

    public String alias() {
        return alias;
    }

    public ObjectNode vars() {
        return vars;
    }

    public boolean replayed() {
        return replayed;
    }

    public Long durationMs() {
        return durationMs;
    }

    public void start(Instant at, JsonNode renderedInput) {
        this.status = "running";
        this.attempt++;
        this.startedAt = at;
        this.endedAt = null;
        this.durationMs = null;
        this.input = renderedInput;
        this.error = null;
    }

    public void waiting() {
        this.status = "waiting";
    }

    public void succeed(Instant at, JsonNode result) {
        this.status = "succeeded";
        this.output = result;
        end(at);
    }

    public void fail(Instant at, StepError why) {
        this.status = "failed";
        this.error = why;
        end(at);
    }

    public void skip(Instant at) {
        this.status = "skipped";
        end(at);
    }

    public void compensated(Instant at) {
        this.status = "compensated";
        end(at);
    }

    public void items(int count) {
        this.items = count;
    }

    public void alias(String as) {
        this.alias = as;
    }

    public void vars(ObjectNode set) {
        this.vars = set;
    }

    public void replayedFrom(StepRecord source, Instant at) {
        this.status = source.status;
        this.attempt = source.attempt;
        this.startedAt = at;
        this.endedAt = at;
        this.durationMs = 0L;
        this.input = source.input;
        this.output = source.output;
        this.error = source.error;
        this.items = source.items;
        this.alias = source.alias;
        this.vars = source.vars;
        this.replayed = true;
    }

    private void end(Instant at) {
        this.endedAt = at;
        if (startedAt != null) {
            this.durationMs = Math.max(0, at.toEpochMilli() - startedAt.toEpochMilli());
        }
    }

    public ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("id", id);
        out.put("name", name);
        out.put("type", type);
        out.put("status", status);
        out.put("attempt", attempt);
        if (startedAt != null) {
            out.put("startedAt", startedAt.toString());
        }
        if (endedAt != null) {
            out.put("endedAt", endedAt.toString());
        }
        if (durationMs != null) {
            out.put("durationMs", durationMs);
        }
        if (input != null && !input.isNull()) {
            out.set("input", input);
        }
        if (output != null && !output.isNull()) {
            out.set("output", output);
        }
        if (error != null) {
            out.set("error", error.document());
        }
        if (items != null) {
            out.put("items", items);
        }
        return out;
    }
}

package com.iaf.platform.runs.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.expressions.ExpressionContext;
import com.iaf.platform.workflows.domain.Step;

/**
 * Everything a running job knows: its input, the outputs of the steps that ran (by id and by
 * name), the aliases from {@code output: { as }}, the variables from {@code set}, who asked,
 * the environment, and the {@code $job} block ({@code id}, {@code requester}, {@code startedAt},
 * a {@code seq} counter the recorded samples mint ids from). Shared by the branches of a fork,
 * so every map is concurrent. {@link #view(JsonNode)} is the expression context of one step,
 * with {@code $json} bound to the previous step's output.
 */
public final class JobContext {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final String jobId;
    private final String workflow;
    private final String version;
    private final JsonNode input;
    private final Identity identity;
    private final String environment;
    private final Instant startedAt;
    private final String traceId;
    private final String parentId;
    private final Map<String, JsonNode> outputs = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> aliases = new ConcurrentHashMap<>();
    private final ObjectNode vars = F.objectNode();
    private final AtomicInteger seq = new AtomicInteger();
    private final List<String> children = Collections.synchronizedList(new ArrayList<>());
    private final List<Completed> completed = Collections.synchronizedList(new ArrayList<>());
    private final List<Registered> registered = Collections.synchronizedList(new ArrayList<>());
    private volatile JsonNode error = NullNode.getInstance();

    /** A step that finished successfully, kept for compensation (reverse order on failure). */
    public record Completed(Step step, JsonNode input, JsonNode output) {
    }

    /** A resource a register step of this job recorded in the registry. */
    public record Registered(String id, String type) {
    }

    public JobContext(String jobId, String workflow, String version, JsonNode input, Identity identity, String environment,
            Instant startedAt, String traceId, String parentId) {
        this.jobId = jobId;
        this.workflow = workflow;
        this.version = version;
        // a copy: building a resource first replaces a create request with the new id (resolveInput)
        this.input = input == null ? F.objectNode() : input.deepCopy();
        this.identity = identity == null ? Identity.SYSTEM : identity;
        this.environment = environment == null ? "dev" : environment;
        this.startedAt = startedAt == null ? Instant.EPOCH : startedAt;
        this.traceId = traceId;
        this.parentId = parentId;
    }

    public static JobContext of(Job job) {
        return new JobContext(job.id(), job.workflow(), job.version(), job.input(), job.requester(), job.environment(),
                job.startedAt(), job.traceId(), job.parentId());
    }

    public String jobId() {
        return jobId;
    }

    public String workflow() {
        return workflow;
    }

    public String version() {
        return version;
    }

    public JsonNode input() {
        return input;
    }

    public Identity identity() {
        return identity;
    }

    public String environment() {
        return environment;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public String traceId() {
        return traceId;
    }

    public String parentId() {
        return parentId;
    }

    /** The next value of the per-job counter recorded samples mint ids from ({@code vm-{{ $job.seq }}}). */
    public int nextSeq() {
        return seq.incrementAndGet();
    }

    public int seq() {
        return seq.get();
    }

    public List<String> children() {
        return List.copyOf(children);
    }

    public void addChild(String childJobId) {
        children.add(childJobId);
    }

    /** Replace one top-level input field, as {@code $input} reads it from now on (an orCreateWith request becomes the new id). */
    public void resolveInput(String field, JsonNode value) {
        if (input instanceof ObjectNode o) {
            o.set(field, value == null ? NullNode.getInstance() : value);
        }
    }

    /** The resources this job's register steps recorded, in order. */
    public List<Registered> registered() {
        return List.copyOf(registered);
    }

    public void addRegistered(String id, String type) {
        if (id != null && type != null) {
            registered.add(new Registered(id, type));
        }
    }

    public List<Completed> completed() {
        return List.copyOf(completed);
    }

    public void completed(Step step, JsonNode stepInput, JsonNode output) {
        completed.add(new Completed(step, stepInput, output));
    }

    /** Record a step's output under its id and its name, plus its alias when it has one. */
    public void recordOutput(Step step, JsonNode output) {
        JsonNode value = output == null ? NullNode.getInstance() : output;
        outputs.put(step.id(), value);
        if (step.name() != null && !step.name().isBlank()) {
            outputs.put(step.name(), value);
        }
        if (step.output() != null && step.output().as() != null) {
            aliases.put(step.output().as(), value);
        }
    }

    public JsonNode output(String idOrName) {
        return outputs.get(idOrName);
    }

    public Map<String, JsonNode> outputs() {
        return Map.copyOf(outputs);
    }

    public void bindAlias(String name, JsonNode value) {
        aliases.put(name, value == null ? NullNode.getInstance() : value);
    }

    public void unbindAlias(String name) {
        aliases.remove(name);
    }

    public JsonNode alias(String name) {
        return aliases.get(name);
    }

    public void setVar(String name, JsonNode value) {
        synchronized (vars) {
            vars.set(name, value == null ? NullNode.getInstance() : value);
        }
    }

    public JsonNode vars() {
        synchronized (vars) {
            return vars.deepCopy();
        }
    }

    public void setError(JsonNode e) {
        this.error = e == null ? NullNode.getInstance() : e;
    }

    public JsonNode error() {
        return error;
    }

    /** The {@code $job} block. */
    public ObjectNode job() {
        ObjectNode j = F.objectNode();
        j.put("id", jobId);
        j.put("workflow", workflow);
        j.put("version", version);
        j.put("requester", identity.id());
        j.put("startedAt", startedAt.toString());
        j.put("environment", environment);
        j.put("seq", seq.get());
        if (traceId != null) {
            j.put("traceId", traceId);
        }
        if (parentId != null) {
            j.put("parentId", parentId);
        }
        return j;
    }

    /** The {@code $env} block. */
    public ObjectNode env() {
        ObjectNode e = F.objectNode();
        e.put("name", environment);
        return e;
    }

    /** The expression context of one step: {@code $json} is the previous step's output. */
    public ExpressionContext view(JsonNode json) {
        JobContext self = this;
        JsonNode previous = json == null ? NullNode.getInstance() : json;
        return new ExpressionContext() {
            @Override
            public JsonNode input() {
                return self.input;
            }

            @Override
            public JsonNode json() {
                return previous;
            }

            @Override
            public JsonNode step(String nameOrId) {
                return self.outputs.get(nameOrId);
            }

            @Override
            public JsonNode alias(String name) {
                return self.aliases.get(name);
            }

            @Override
            public JsonNode vars() {
                return self.vars();
            }

            @Override
            public JsonNode job() {
                return self.job();
            }

            @Override
            public JsonNode env() {
                return self.env();
            }

            @Override
            public JsonNode error() {
                return self.error;
            }
        };
    }
}

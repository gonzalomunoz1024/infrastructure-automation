package com.iaf.console.fake.providers.domain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.content.domain.Expressions;
import com.iaf.console.fake.content.domain.ProviderDef;

/**
 * What the providers answer: every action's recorded sample, rendered for the call, plus what a
 * demo changed since ({@code POST /api/v1/demo/world:mutate}): fields set on one object, objects
 * deleted, and failures the next calls must answer with.
 */
public final class RecordedWorld {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** A failure a demo asked for, answered by the next {@code times} calls of one action on one object. */
    private record Planned(String errorClass, String message, boolean retryable, int status, AtomicLong times) {
    }

    private final Map<String, ObjectNode> changed = new ConcurrentHashMap<>();
    private final Map<String, Boolean> deleted = new ConcurrentHashMap<>();
    private final Map<String, Planned> failures = new ConcurrentHashMap<>();
    private final AtomicLong seq;

    public RecordedWorld(long firstSeq) {
        this.seq = new AtomicLong(firstSeq);
    }

    /** The next value of {@code $job.seq}, one counter for the whole world so sample ids never repeat. */
    public long nextSeq() {
        return seq.getAndIncrement();
    }

    /**
     * Answers one action from its sample, with {@code $input} the rendered input and {@code $job}
     * the calling job, honouring what the world remembers about the object the input names.
     */
    public JsonNode call(ProviderDef provider, String actionId, JsonNode input, ObjectNode job) {
        JsonNode action = provider.action(actionId);
        if (action == null) {
            throw new ProviderFailure("unknown_action", provider.name() + " has no action " + actionId, false, 400);
        }
        String effect = provider.effect(actionId);
        String id = idOf(input);
        String key = id == null ? null : provider.name() + ":" + id;
        Planned planned = key == null ? failures.get(provider.name() + ":" + actionId + ":*") : firstPlanned(provider.name(), actionId, id);
        if (planned != null && planned.times().getAndDecrement() > 0) {
            throw new ProviderFailure(planned.errorClass(), planned.message(), planned.retryable(), planned.status());
        }
        if (key != null && "read".equals(effect) && deleted.containsKey(key)) {
            throw new ProviderFailure("not_found", provider.name() + " " + actionId + ": " + id + " not found", false, 404);
        }
        ObjectNode jobScope = job == null ? NODES.objectNode() : job.deepCopy();
        if (!jobScope.has("seq")) {
            jobScope.put("seq", nextSeq());
        }
        Expressions.MapScope scope = new Expressions.MapScope()
                .put("input", input == null ? NODES.objectNode() : input)
                .put("json", input == null ? NODES.objectNode() : input)
                .put("job", jobScope);
        JsonNode sample = action.path("sample");
        JsonNode out;
        if (sample.isMissingNode() || sample.isNull()) {
            out = NODES.objectNode().put("ok", true);
        } else {
            out = Expressions.render(sample, scope);
        }
        if (key == null && "create".equals(effect) && idOf(out) != null) {
            // a create names its object only in its answer; reads of it then answer what the create asked for
            key = provider.name() + ":" + idOf(out);
            changed.put(key, remembered(input));
        }
        if (key != null && out.isObject()) {
            ObjectNode object = (ObjectNode) out;
            if ("delete".equals(effect)) {
                deleted.put(key, Boolean.TRUE);
                changed.remove(key);
            } else if ("create".equals(effect)) {
                deleted.remove(key);
            }
            ObjectNode overlay = changed.get(key);
            if (overlay != null && "read".equals(effect)) {
                overlay.properties().forEach(e -> object.set(e.getKey(), e.getValue().deepCopy()));
            }
            if ("change".equals(effect) && input != null && input.isObject()) {
                changed.computeIfAbsent(key, k -> NODES.objectNode()).setAll(remembered(input));
            }
        }
        return out;
    }

    /** An input's fields but its id, as reads of the object it names will answer them. */
    private static ObjectNode remembered(JsonNode input) {
        ObjectNode out = NODES.objectNode();
        if (input != null && input.isObject()) {
            input.properties().forEach(e -> {
                if (!"id".equals(e.getKey())) {
                    out.set(e.getKey(), e.getValue().deepCopy());
                }
            });
        }
        return out;
    }

    /** Renders an action's sample for a dry run: no counter moves, nothing is remembered, no planned failure fires. */
    public JsonNode preview(ProviderDef provider, String actionId, JsonNode input) {
        JsonNode action = provider.action(actionId);
        if (action == null) {
            throw new ProviderFailure("unknown_action", provider.name() + " has no action " + actionId, false, 400);
        }
        ObjectNode jobScope = NODES.objectNode();
        jobScope.put("seq", 0);
        Expressions.MapScope scope = new Expressions.MapScope()
                .put("input", input == null ? NODES.objectNode() : input)
                .put("json", input == null ? NODES.objectNode() : input)
                .put("job", jobScope);
        JsonNode sample = action.path("sample");
        if (sample.isMissingNode() || sample.isNull()) {
            return NODES.objectNode().put("ok", true);
        }
        JsonNode out = Expressions.render(sample, scope);
        if (out.isObject() && idOf(input) != null) {
            return withChanges(provider.name(), (ObjectNode) out);
        }
        return out;
    }

    /** The overlay a demo set on one object, merged into a provider object a list action answers. */
    public ObjectNode withChanges(String provider, ObjectNode object) {
        String id = idOf(object);
        if (id == null) {
            return object;
        }
        ObjectNode overlay = changed.get(provider + ":" + id);
        if (overlay == null) {
            return object;
        }
        ObjectNode out = object.deepCopy();
        overlay.properties().forEach(e -> out.set(e.getKey(), e.getValue().deepCopy()));
        return out;
    }

    public boolean isDeleted(String provider, String id) {
        return deleted.containsKey(provider + ":" + id);
    }

    /**
     * Changes the world: {@code { provider, action?, match: { id }, set?, delete?, fail?: { class, message, retryable, times } }}.
     * {@code set} changes what reads of the object answer, {@code delete} makes reads answer not
     * found, {@code fail} makes the next calls fail; the answer says what the world now holds.
     */
    public ObjectNode mutate(JsonNode body) {
        String provider = body.path("provider").asText(null);
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        String id = body.path("match").path("id").asText(null);
        String action = body.path("action").asText(null);
        if (id == null && body.path("fail").isMissingNode()) {
            throw new IllegalArgumentException("match.id is required");
        }
        String key = provider + ":" + id;
        if (body.path("set").isObject()) {
            ObjectNode overlay = changed.computeIfAbsent(key, k -> NODES.objectNode());
            body.path("set").properties().forEach(e -> overlay.set(e.getKey(), e.getValue().deepCopy()));
            deleted.remove(key);
        }
        if (body.path("delete").asBoolean(false)) {
            deleted.put(key, Boolean.TRUE);
        }
        if (body.path("revive").asBoolean(false)) {
            deleted.remove(key);
            changed.remove(key);
        }
        JsonNode fail = body.path("fail");
        if (fail.isObject()) {
            String failKey = provider + ":" + (action == null ? "*" : action) + ":" + (id == null ? "*" : id);
            failures.put(failKey, new Planned(fail.path("class").asText("unavailable"), fail.path("message").asText("the provider failed"),
                    fail.path("retryable").asBoolean(true), fail.path("status").asInt(503), new AtomicLong(fail.path("times").asLong(1))));
        } else if (fail.isNull()) {
            failures.keySet().removeIf(k -> k.startsWith(provider + ":"));
        }
        ObjectNode out = NODES.objectNode();
        out.put("provider", provider);
        if (id != null) {
            out.put("id", id);
            out.put("deleted", deleted.containsKey(key));
            ObjectNode overlay = changed.get(key);
            out.set("changes", overlay == null ? NODES.objectNode() : overlay.deepCopy());
        }
        out.put("plannedFailures", failures.size());
        return out;
    }

    private Planned firstPlanned(String provider, String actionId, String id) {
        Planned exact = failures.get(provider + ":" + actionId + ":" + id);
        if (exact != null) {
            return exact;
        }
        Planned anyAction = failures.get(provider + ":*:" + id);
        if (anyAction != null) {
            return anyAction;
        }
        return failures.get(provider + ":" + actionId + ":*");
    }

    /** The object an input names: its {@code id}, else its {@code clusterId}, {@code buildId}, {@code imageId} or {@code vmId}. */
    public static String idOf(JsonNode input) {
        if (input == null || !input.isObject()) {
            return null;
        }
        for (String field : new String[] {"id", "clusterId", "buildId", "imageId", "vmId", "address"}) {
            JsonNode value = input.get(field);
            if (value != null && value.isValueNode() && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }
}

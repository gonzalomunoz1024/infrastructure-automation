package com.iaf.platform.runs.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.common.expressions.ExpressionContext;
import com.iaf.platform.common.expressions.ExpressionEngine;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Trigger;

/**
 * Which published workflows an alert or an event starts. An alert trigger matches when its
 * {@code match} is a subset of the payload: the source is equal and every listed label is equal.
 * An event trigger matches on its topic ({@code vm.created}, or a glob like {@code vm.*}). The
 * trigger's {@code input} templates are resolved with the alias {@code $alert} or {@code $event}
 * bound to the payload.
 */
public final class TriggerMatching {

    private TriggerMatching() {
    }

    /** A workflow to start and the input its trigger produced. */
    public record Match(WorkflowDefinition workflow, Trigger trigger, JsonNode input) {
    }

    public static List<Match> alerts(List<WorkflowDefinition> published, JsonNode alert) {
        List<Match> out = new ArrayList<>();
        for (WorkflowDefinition d : published) {
            for (Trigger t : d.triggers() == null ? List.<Trigger>of() : d.triggers()) {
                if ("alert".equals(t.type()) && matchesAlert(t.spec() == null ? null : t.spec().get("match"), alert)) {
                    out.add(new Match(d, t, resolveInput(t.spec() == null ? null : t.spec().get("input"), "alert", alert)));
                }
            }
        }
        return out;
    }

    public static List<Match> events(List<WorkflowDefinition> published, String topic, JsonNode payload) {
        List<Match> out = new ArrayList<>();
        for (WorkflowDefinition d : published) {
            for (Trigger t : d.triggers() == null ? List.<Trigger>of() : d.triggers()) {
                if ("event".equals(t.type()) && matchesTopic(t.spec() == null ? null : t.spec().path("topic").asText(null), topic)) {
                    out.add(new Match(d, t, resolveInput(t.spec() == null ? null : t.spec().get("input"), "event", payload)));
                }
            }
        }
        return out;
    }

    /** {@code match} is a subset of the alert: equal source (when given), every label equal (when given). */
    public static boolean matchesAlert(JsonNode match, JsonNode alert) {
        if (match == null || !match.isObject() || alert == null || !alert.isObject()) {
            return false;
        }
        JsonNode source = match.get("source");
        if (source != null && !source.isNull() && !source.asText().equals(alert.path("source").asText(null))) {
            return false;
        }
        JsonNode labels = match.get("labels");
        if (labels != null && labels.isObject()) {
            JsonNode actual = alert.path("labels");
            var it = labels.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = actual.get(e.getKey());
                if (v == null || v.isNull() || !e.getValue().asText().equals(v.asText())) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean matchesTopic(String pattern, String topic) {
        if (pattern == null || topic == null) {
            return false;
        }
        if (pattern.equals(topic) || "*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith("*")) {
            return topic.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return false;
    }

    public static JsonNode resolveInput(JsonNode template, String alias, JsonNode payload) {
        if (template == null || template.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }
        JsonNode p = payload == null ? JsonNodeFactory.instance.objectNode() : payload;
        ExpressionContext ctx = ExpressionContext.of(p, p, Map.of(), Map.of(alias, p), null, null, null, null);
        return ExpressionEngine.resolve(template, ctx);
    }
}

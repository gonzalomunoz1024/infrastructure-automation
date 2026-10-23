package com.iaf.console.drift.scorecards.domain;

import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.DriftRules;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Watch;

/**
 * The document every rule is judged on, the same for the embedded engine and OPA:
 * {@code { resource: { wfrn, accountId, region, type, service, name, state, environment,
 * production, attributes, observed, effective, tags } }}, where {@code effective} is the desired
 * attributes overlaid with what the provider last reported.
 */
public final class EvaluationInput {

    private EvaluationInput() {
    }

    public static ObjectNode of(Watch watch, ObjectNode observed) {
        ObjectNode input = Json.object();
        ObjectNode r = input.putObject("resource");
        r.put("wfrn", watch.wfrn());
        r.put("accountId", watch.accountId());
        r.put("region", watch.region());
        r.put("type", watch.type());
        put(r, "service", watch.service());
        put(r, "name", watch.name());
        r.put("state", watch.state());
        put(r, "environment", watch.environment());
        r.put("production", watch.production());
        r.set("attributes", watch.desired().deepCopy());
        ObjectNode seen = observed == null ? Json.object() : observed.deepCopy();
        r.set("observed", seen);
        ObjectNode effective = watch.desired().deepCopy();
        for (Iterator<Map.Entry<String, JsonNode>> it = seen.properties().iterator(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isNull()) {
                effective.set(e.getKey(), e.getValue());
            }
        }
        r.set("effective", effective);
        ObjectNode tags = r.putObject("tags");
        watch.tags().forEach(tags::put);
        return input;
    }

    /** A digest of the input as the rules see it, so an unchanged read skips the engine. */
    public static String digest(ObjectNode input) {
        return Json.sha256(DriftRules.normalize(input).toString());
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}

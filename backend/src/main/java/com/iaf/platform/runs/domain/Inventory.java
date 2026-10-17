package com.iaf.platform.runs.domain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/**
 * The inventory check of a start: every input marked {@code x-resource} must name a resource the
 * registry knows, of that type, and not retired. A resource that is still building passes; the
 * {@code use} step waits for it.
 */
public final class Inventory {

    public static final String RETIRED = "retired";

    /** An input that points at the registry. */
    public record Field(String name, String type) {
    }

    private Inventory() {
    }

    public static List<Field> fields(WorkflowDefinition def) {
        JsonNode props = def.inputSchema() == null ? null : def.inputSchema().get("properties");
        List<Field> out = new ArrayList<>();
        if (props == null || !props.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode xr = e.getValue().get("x-resource");
            String type = xr == null ? null : xr.path("type").asText(null);
            if (type != null && !type.isBlank()) {
                out.add(new Field(e.getKey(), type));
            }
        }
        return out;
    }

    /** The ids the rows name, to look up once. */
    public static Set<String> ids(List<Field> fields, List<JsonNode> rows) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            for (Field f : fields) {
                JsonNode v = row == null ? null : row.get(f.name());
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    ids.add(v.asText());
                }
            }
        }
        return ids;
    }

    /** The problems of one row, given the resources found by id. */
    public static List<Problem> problems(List<Field> fields, JsonNode row, Map<String, Resource> found) {
        List<Problem> out = new ArrayList<>();
        for (Field f : fields) {
            JsonNode v = row == null ? null : row.get(f.name());
            if (v == null || !v.isTextual() || v.asText().isBlank()) {
                continue;
            }
            String id = v.asText();
            Resource r = found.get(id);
            if (r == null) {
                out.add(Problem.error("input.unknownResource", null, f.name(), "No " + f.type() + " '" + id + "' in the registry"));
            } else if (!f.type().equals(r.type())) {
                out.add(Problem.error("input.wrongResourceType", null, f.name(), "'" + id + "' is a " + r.type() + ", not a " + f.type()));
            } else if (RETIRED.equals(r.status())) {
                out.add(Problem.error("input.retiredResource", null, f.name(), f.type() + " '" + id + "' is retired"));
            }
        }
        return out;
    }
}

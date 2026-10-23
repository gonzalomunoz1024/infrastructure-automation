package com.iaf.console.state.inventory.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * "Revert to this": what it takes to bring a live resource back to how it was at an earlier point.
 * {@code changes} is the JSON merge patch (RFC 7386) from the attributes now to the attributes then,
 * ready for {@code :request-update} and the type's update workflow; {@code tags} the tag changes for
 * {@code :tag}; {@code fields} each changed leaf, now and then, for the confirmation page. When the
 * resource cannot be reverted, {@code possible} is false and {@code reason} says why.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RevertPlan(String wfrn, Point from, Point to, boolean possible, String reason, ObjectNode changes, TagChanges tags,
        List<FieldChange> fields) {

    /** One end of the plan: the resource's version, when it got there, and its state. */
    public record Point(long version, Instant at, ResourceState state) {
        static Point of(Resource r) {
            return new Point(r.version(), r.updatedAt(), r.state());
        }
    }

    public record TagChanges(Map<String, String> set, List<String> removed) {
    }

    public record FieldChange(String path, JsonNode now, JsonNode then) {
    }

    /** The plan from the resource now to the resource then (null when it did not exist then). */
    public static RevertPlan of(Resource now, Resource then) {
        Point from = Point.of(now);
        if (then == null) {
            return new RevertPlan(now.wfrn(), from, null, false, "resource.notYet", null, null, null);
        }
        Point to = Point.of(then);
        String refused = switch (now.state()) {
            case DELETED -> "resource.deleted";
            case PENDING, UPDATING, DELETING -> "resource.busy";
            default -> switch (then.state()) {
                case DELETED -> "resource.wasDeleted";
                case PENDING, FAILED -> "resource.wasNotProvisioned";
                default -> null;
            };
        };
        if (refused != null) {
            return new RevertPlan(now.wfrn(), from, to, false, refused, null, null, null);
        }
        ObjectNode a = Json.orEmpty(now.attributes());
        ObjectNode b = Json.orEmpty(then.attributes());
        JsonNode patch = diff(a, b);
        ObjectNode changes = patch instanceof ObjectNode o ? o : Json.object();
        List<FieldChange> fields = new ArrayList<>();
        leaves("", changes, a, b, fields);
        Map<String, String> set = new TreeMap<>();
        then.tags().forEach((k, v) -> {
            if (!v.equals(now.tags().get(k))) {
                set.put(k, v);
            }
        });
        List<String> removed = now.tags().keySet().stream().filter(k -> !then.tags().containsKey(k)).sorted().toList();
        boolean unchanged = changes.isEmpty() && set.isEmpty() && removed.isEmpty();
        return new RevertPlan(now.wfrn(), from, to, !unchanged, unchanged ? "resource.unchanged" : null, changes, new TagChanges(set, removed), fields);
    }

    /** The merge patch that turns {@code a} into {@code b}: removed members are null, objects recurse, anything else is replaced. */
    public static JsonNode diff(JsonNode a, JsonNode b) {
        if (!(a instanceof ObjectNode from) || !(b instanceof ObjectNode to)) {
            return b == null ? NullNode.getInstance() : b.deepCopy();
        }
        ObjectNode patch = Json.object();
        from.fieldNames().forEachRemaining(name -> {
            if (!to.has(name)) {
                patch.putNull(name);
            }
        });
        for (Map.Entry<String, JsonNode> field : to.properties()) {
            JsonNode before = from.get(field.getKey());
            if (before == null) {
                patch.set(field.getKey(), field.getValue().deepCopy());
            } else if (!Objects.equals(before, field.getValue())) {
                patch.set(field.getKey(), diff(before, field.getValue()));
            }
        }
        return patch;
    }

    private static void leaves(String prefix, ObjectNode patch, JsonNode now, JsonNode then, List<FieldChange> out) {
        for (Map.Entry<String, JsonNode> field : patch.properties()) {
            String path = prefix + "/" + field.getKey().replace("~", "~0").replace("/", "~1");
            JsonNode a = now == null ? null : now.get(field.getKey());
            JsonNode b = then == null ? null : then.get(field.getKey());
            if (field.getValue() instanceof ObjectNode nested && a instanceof ObjectNode && b instanceof ObjectNode) {
                leaves(path, nested, a, b, out);
            } else {
                out.add(new FieldChange(path, a, b));
            }
        }
    }
}

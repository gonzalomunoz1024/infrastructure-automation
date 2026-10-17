package com.iaf.platform.runs.adapters.outbound;

import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.runs.domain.InputDefaults;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/**
 * Inputs for the seeded history. A workflow's input schema drives the shape (defaults, enums,
 * ids for {@code x-resource} fields); the pools below only make the values look like a real
 * fortnight of requests. Unknown workflows get the schema's sample.
 */
final class SeedInputs {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private static final List<String> VM_IDS = List.of("vm-1042", "vm-1043", "vm-2001", "vm-1107", "vm-1188", "vm-3310", "vm-0719", "vm-0744");
    private static final List<String> DB_IDS = List.of("db-9", "db-12", "db-3");
    private static final List<String> IMAGE_IDS = List.of("img-77", "img-1", "img-104");
    private static final List<String> NAMES = List.of("web", "api", "batch", "cache", "worker", "shop", "blog", "portal", "orders", "payments", "inventory");
    private static final List<String> REASONS = List.of("stuck after kernel update", "high load, unresponsive", "scheduled maintenance",
            "decommissioned after migration", "memory leak in agent", "requested by the on-call", "disk full, services restarting");
    private static final List<String> WINDOWS = List.of("Sat 02:00-04:00 UTC", "Sun 01:00-03:00 UTC", "tonight 23:00", "next maintenance window");

    private static final Map<String, List<String>> ENUM_WEIGHTS = Map.of(
            "environment", List.of("dev", "dev", "dev", "test", "test", "prod"),
            "size", List.of("small", "small", "medium", "large"),
            "severity", List.of("high", "high", "critical", "medium"),
            "tier", List.of("M10", "M10", "M30", "standard", "premium"),
            "targetVersion", List.of("8.0", "8.0", "7.0"));

    private SeedInputs() {
    }

    static JsonNode inputFor(WorkflowDefinition def, Random rnd) {
        JsonNode schema = def.inputSchema();
        ObjectNode out = F.objectNode();
        if (schema == null || !schema.path("properties").isObject()) {
            return out;
        }
        int n = 100 + rnd.nextInt(900);
        schema.get("properties").fields().forEachRemaining(e -> {
            String name = e.getKey();
            JsonNode p = e.getValue();
            JsonNode xr = p.get("x-resource");
            if (xr != null && xr.isObject()) {
                out.put(name, resourceId(xr.path("type").asText(""), rnd));
                return;
            }
            if (p.has("enum") && p.get("enum").isArray() && p.get("enum").size() > 0) {
                List<String> weighted = ENUM_WEIGHTS.get(name);
                if (weighted != null) {
                    List<String> allowed = new java.util.ArrayList<>();
                    p.get("enum").forEach(v -> allowed.add(v.asText()));
                    List<String> candidates = weighted.stream().filter(allowed::contains).toList();
                    if (!candidates.isEmpty()) {
                        out.put(name, candidates.get(rnd.nextInt(candidates.size())));
                        return;
                    }
                }
                JsonNode v = p.get("enum").get(rnd.nextInt(p.get("enum").size()));
                out.set(name, v);
                return;
            }
            String type = p.path("type").asText("string");
            switch (name) {
                case "name" -> out.put(name, NAMES.get(rnd.nextInt(NAMES.size())) + "-" + String.format("%02d", 1 + rnd.nextInt(40)));
                case "reason" -> out.put(name, REASONS.get(rnd.nextInt(REASONS.size())));
                case "maintenanceWindow" -> out.put(name, WINDOWS.get(rnd.nextInt(WINDOWS.size())));
                case "cve" -> out.put(name, "CVE-2026-1234");
                case "owner" -> out.put(name, rnd.nextBoolean() ? "team-compute" : "team-platform");
                case "sizeGb" -> out.put(name, List.of(100, 200, 500).get(rnd.nextInt(3)));
                case "vmCount" -> out.put(name, 2 + rnd.nextInt(2));
                case "keepSnapshotDays" -> out.put(name, 7);
                default -> {
                    if (p.has("default")) {
                        out.set(name, p.get("default"));
                    } else if ("integer".equals(type) || "number".equals(type)) {
                        out.put(name, n);
                    } else if ("boolean".equals(type)) {
                        out.put(name, rnd.nextBoolean());
                    } else if ("string".equals(type)) {
                        if (schema.path("required").toString().contains("\"" + name + "\"")) {
                            out.set(name, Schemas.sample(p));
                        }
                    } else {
                        out.set(name, Schemas.sample(p));
                    }
                }
            }
        });
        return InputDefaults.apply(schema, out);
    }

    private static String resourceId(String type, Random rnd) {
        return switch (type) {
            case "vm" -> VM_IDS.get(rnd.nextInt(VM_IDS.size()));
            case "database" -> DB_IDS.get(rnd.nextInt(DB_IDS.size()));
            case "image" -> IMAGE_IDS.get(rnd.nextInt(IMAGE_IDS.size()));
            default -> type + "-" + (100 + rnd.nextInt(900));
        };
    }
}

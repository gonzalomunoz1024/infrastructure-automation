package com.iaf.console.drift.drift.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The pure comparison of the plan's {@code DriftRules}: both sides are normalised (numbers,
 * booleans and numeric strings compare by value, strings are trimmed, lists of scalars ignore
 * their order, objects their key order), the type's {@code ignore} fields and the fields the
 * provider reports but no workflow sets are left out, the fields a decision silenced are listed
 * but not counted, and the severity follows from what drifted and where.
 */
public final class DriftRules {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private DriftRules() {
    }

    /** What a read said against the desired state: the fields, whether anything counts, and how much it matters. */
    public record Comparison(List<FindingField> fields, Severity severity) {

        public Comparison {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        public static Comparison inSync() {
            return new Comparison(List.of(), Severity.INFO);
        }

        /** Whether a finding should be open: at least one field that counts. */
        public boolean drifted() {
            return fields.stream().anyMatch(FindingField::counts);
        }
    }

    /**
     * Compares the desired attributes with what the provider reports (the type's mapped fields).
     * A mapped field the desired state does not have is observed-only and never drift.
     */
    public static Comparison compare(ObjectNode desired, JsonNode observed, Set<String> ignore, Collection<IgnoredField> silenced, Set<String> mutable,
            boolean production) {
        List<FindingField> fields = new ArrayList<>();
        if (observed != null && observed.isObject() && desired != null) {
            Set<String> silencedPaths = new HashSet<>();
            for (IgnoredField s : silenced) {
                silencedPaths.add(s.path());
            }
            List<String> names = new ArrayList<>();
            observed.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                if (ignore.contains(name) || !desired.has(name)) {
                    continue;
                }
                JsonNode want = desired.get(name);
                JsonNode got = observed.get(name);
                if (same(want, got)) {
                    continue;
                }
                FindingField field = FindingField.of(name, want, got);
                fields.add(silencedPaths.contains(field.path()) ? field.silenced() : field);
            }
        }
        return new Comparison(fields, severityOf(fields, mutable, production, false));
    }

    /** The provider no longer has the resource and the type says that is drift ({@code notFound: missing}). */
    public static Comparison missing(ObjectNode desired, Collection<IgnoredField> silenced) {
        FindingField whole = new FindingField(FindingField.WHOLE, desired == null ? Json.object() : desired, NODES.nullNode(), null);
        for (IgnoredField s : silenced) {
            if (FindingField.WHOLE.equals(s.path())) {
                whole = whole.silenced();
            }
        }
        return new Comparison(List.of(whole), Severity.CRITICAL);
    }

    /**
     * Missing or in production is critical; a field the console can put back (mutable) is a
     * warning; anything else is information, since only accept or ignore can settle it.
     */
    public static Severity severityOf(List<FindingField> fields, Set<String> mutable, boolean production, boolean missing) {
        if (missing || fields.stream().anyMatch(f -> f.counts() && FindingField.WHOLE.equals(f.path()))) {
            return Severity.CRITICAL;
        }
        if (fields.stream().noneMatch(FindingField::counts)) {
            return Severity.INFO;
        }
        if (production) {
            return Severity.CRITICAL;
        }
        boolean anyMutable = fields.stream().anyMatch(f -> f.counts() && f.fieldName() != null && mutable.contains(f.fieldName()));
        return anyMutable ? Severity.WARNING : Severity.INFO;
    }

    /** Whether every field that counts can be put back by the type's update workflow. */
    public static List<String> notRevertable(List<FindingField> fields, Set<String> mutable) {
        List<String> out = new ArrayList<>();
        for (FindingField f : fields) {
            if (!f.counts()) {
                continue;
            }
            String name = f.fieldName();
            if (name == null || !mutable.contains(name)) {
                out.add(f.path());
            }
        }
        return out;
    }

    public static boolean same(JsonNode a, JsonNode b) {
        return normalize(a).equals(normalize(b));
    }

    /** The canonical form two values are compared in. */
    public static JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return NODES.nullNode();
        }
        if (node.isNumber()) {
            return NODES.textNode("#" + node.decimalValue().stripTrailingZeros().toPlainString());
        }
        if (node.isBoolean()) {
            return NODES.textNode("?" + node.booleanValue());
        }
        if (node.isTextual()) {
            String text = node.textValue().trim();
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                return NODES.textNode("?" + text.toLowerCase(java.util.Locale.ROOT));
            }
            BigDecimal number = asNumber(text);
            if (number != null) {
                return NODES.textNode("#" + number.stripTrailingZeros().toPlainString());
            }
            Instant at = asInstant(text);
            if (at != null) {
                return NODES.textNode("@" + at);
            }
            return NODES.textNode(text);
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            boolean scalars = true;
            for (JsonNode item : node) {
                JsonNode n = normalize(item);
                scalars &= !n.isContainerNode();
                items.add(n);
            }
            if (scalars) {
                items.sort(Comparator.comparing(JsonNode::toString));
            }
            ArrayNode out = NODES.arrayNode();
            items.forEach(out::add);
            return out;
        }
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                JsonNode n = normalize(e.getValue());
                if (!n.isNull()) {
                    sorted.put(e.getKey(), n);
                }
            }
            ObjectNode out = NODES.objectNode();
            sorted.forEach(out::set);
            return out;
        }
        return NODES.textNode(node.asText());
    }

    private static BigDecimal asNumber(String text) {
        if (text.isEmpty() || text.length() > 40) {
            return null;
        }
        char first = text.charAt(0);
        if (!(Character.isDigit(first) || first == '-' || first == '+' || first == '.')) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant asInstant(String text) {
        if (text.length() < 20 || text.length() > 40 || text.charAt(4) != '-') {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

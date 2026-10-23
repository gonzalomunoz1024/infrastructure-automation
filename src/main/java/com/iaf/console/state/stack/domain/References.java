package com.iaf.console.state.stack.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * The references a blueprint's strings hold: {@code ${params.<name>}},
 * {@code ${resources.<id>.wfrn|registryId|name}}, {@code ${resources.<id>.attributes.<field>}},
 * {@code ${stack.name|wfrn|accountId|region}} and {@code ${teardown.reason}}. A string that is one
 * reference resolves to the referenced value with its type; any other string is text.
 */
public final class References {

    /** One reference in a string. */
    public static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]*)\\}");
    private static final Pattern WHOLE = Pattern.compile("^\\$\\{([^}]*)\\}$");
    public static final Set<String> STACK_FIELDS = Set.of("name", "wfrn", "accountId", "region");
    public static final Set<String> RESOURCE_FIELDS = Set.of("wfrn", "registryId", "name");

    private References() {
    }

    /** What a reference resolves to; {@link Unresolved} when its value is not known (yet). */
    @FunctionalInterface
    public interface Resolver {
        JsonNode resolve(String expression);
    }

    /** A reference whose value is not known: a resource not active yet, or a field it lacks. */
    public static final class Unresolved extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String expression;

        public Unresolved(String expression, String why) {
            super("${" + expression + "}: " + why);
            this.expression = expression;
        }

        public String expression() {
            return expression;
        }
    }

    /** Every reference a value holds, in order, each once. */
    public static Set<String> in(JsonNode value) {
        Set<String> out = new LinkedHashSet<>();
        collect(value, out);
        return out;
    }

    private static void collect(JsonNode value, Set<String> out) {
        if (value == null) {
            return;
        }
        if (value.isTextual()) {
            Matcher m = REFERENCE.matcher(value.asText());
            while (m.find()) {
                out.add(m.group(1).trim());
            }
        } else if (value.isContainerNode()) {
            value.forEach(child -> collect(child, out));
        }
    }

    /** The resource ids the references of a value name. */
    public static Set<String> resourcesIn(JsonNode value) {
        Set<String> out = new LinkedHashSet<>();
        for (String expression : in(value)) {
            String[] parts = expression.split("\\.");
            if (parts.length >= 3 && parts[0].equals("resources")) {
                out.add(parts[1]);
            }
        }
        return out;
    }

    /** The value with every reference resolved; throws {@link Unresolved} when one is not known. */
    public static JsonNode resolve(JsonNode value, Resolver resolver) {
        return walk(value, resolver, false);
    }

    /** The value with the references that can be resolved resolved, the others left as they are. */
    public static JsonNode partial(JsonNode value, Resolver resolver) {
        return walk(value, resolver, true);
    }

    private static JsonNode walk(JsonNode value, Resolver resolver, boolean lenient) {
        if (value == null) {
            return null;
        }
        if (value.isTextual()) {
            return text(value.asText(), resolver, lenient);
        }
        if (value.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                out.set(field.getKey(), walk(field.getValue(), resolver, lenient));
            }
            return out;
        }
        if (value.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            value.forEach(child -> out.add(walk(child, resolver, lenient)));
            return out;
        }
        return value.deepCopy();
    }

    private static JsonNode text(String text, Resolver resolver, boolean lenient) {
        Matcher whole = WHOLE.matcher(text);
        if (whole.matches()) {
            try {
                JsonNode resolved = resolver.resolve(whole.group(1).trim());
                return resolved == null ? JsonNodeFactory.instance.nullNode() : resolved.deepCopy();
            } catch (Unresolved e) {
                if (lenient) {
                    return TextNode.valueOf(text);
                }
                throw e;
            }
        }
        Matcher m = REFERENCE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement;
            try {
                JsonNode resolved = resolver.resolve(m.group(1).trim());
                replacement = resolved == null || resolved.isNull() ? "" : resolved.isValueNode() ? resolved.asText() : resolved.toString();
            } catch (Unresolved e) {
                if (!lenient) {
                    throw e;
                }
                replacement = m.group(0);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return TextNode.valueOf(out.toString());
    }

    /** The problems of one reference where it appears; empty when it is a reference that may stand there. */
    public static List<String> problems(String expression, String self, Set<String> parameters, Set<String> resources, boolean inDeleteInput) {
        List<String> out = new ArrayList<>();
        String[] parts = expression.split("\\.");
        String head = parts[0];
        String where = "${" + expression + "}";
        switch (head) {
            case "params" -> {
                if (parts.length != 2 || !parameters.contains(parts[1])) {
                    out.add(where + " names no parameter");
                }
            }
            case "stack" -> {
                if (parts.length != 2 || !STACK_FIELDS.contains(parts[1])) {
                    out.add(where + ": a stack has " + String.join(", ", new java.util.TreeSet<>(STACK_FIELDS)));
                }
            }
            case "teardown" -> {
                if (parts.length != 2 || !parts[1].equals("reason")) {
                    out.add(where + ": a teardown has a reason");
                } else if (!inDeleteInput) {
                    out.add(where + " is only known to deleteInput");
                }
            }
            case "resources" -> {
                if (inDeleteInput) {
                    out.add(where + ": deleteInput may name parameters, the stack and the teardown, not resources");
                } else if (parts.length < 3 || !resources.contains(parts[1])) {
                    out.add(where + " names no resource");
                } else if (parts[1].equals(self)) {
                    out.add(where + " names the resource itself");
                } else if (!(parts.length == 3 && RESOURCE_FIELDS.contains(parts[2])) && !(parts.length == 4 && parts[2].equals("attributes"))) {
                    out.add(where + ": a resource has wfrn, registryId, name and attributes.<field>");
                }
            }
            default -> out.add(where + " is not a reference (params, resources, stack, teardown)");
        }
        return out;
    }
}

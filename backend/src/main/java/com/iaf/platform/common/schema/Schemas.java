package com.iaf.platform.common.schema;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Problem;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaId;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.ClasspathSchemaLoader;
import com.networknt.schema.resource.DisallowSchemaLoader;
import com.networknt.schema.resource.SchemaLoader;

/**
 * JSON Schema helpers used by the gateway (input validation), the checks (which fields a step
 * returns, whether a schema in a workflow file is one), the wizard (schemas inferred from real
 * responses) and the catalog form.
 *
 * <p>Nothing a schema names is ever fetched: the validator's loaders serve the meta-schemas it
 * bundles and refuse every other IRI, so a {@code $ref} to an http(s) URL in a workflow's
 * {@code api.input} or a resource type's schema cannot make the server request it. The checks
 * refuse such a reference at save time ({@link #remoteRef}, {@code schema.remoteRef}).</p>
 */
public final class Schemas {

    private static final JsonSchemaFactory FACTORY = factory(DisallowSchemaLoader.getInstance());
    /** Violations located by JSON pointers ({@code /workflow/do/0/getVm}) rather than {@code $.workflow...}. */
    private static final SchemaValidatorsConfig POINTERS = SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).build();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Set<String> REF_KEYWORDS = Set.of("$ref", "$dynamicRef", "$recursiveRef");
    /** Keywords whose value is data, not a schema: a {@code $ref} key inside one refers to nothing. */
    private static final Set<String> DATA_KEYWORDS = Set.of("enum", "const", "default", "examples", "example");
    /** Keywords whose value maps names to schemas: the names are not keywords. */
    private static final Set<String> SCHEMA_MAPS = Set.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas");

    private Schemas() {
    }

    /**
     * A factory whose one loader serves the meta-schemas the validator bundles (it maps the
     * json-schema.org IRIs to {@code classpath:draft...}) and hands every other IRI to
     * {@code otherwise}, which must load it or throw: the built-in loaders, which would fetch an
     * http(s) IRI, come after it and are never reached.
     */
    static JsonSchemaFactory factory(SchemaLoader otherwise) {
        ClasspathSchemaLoader bundled = new ClasspathSchemaLoader();
        SchemaLoader loader = iri -> iri.toString().startsWith("classpath:draft") ? bundled.getSchema(iri) : otherwise.getSchema(iri);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, b -> b.schemaLoaders(l -> l.values(list -> {
            list.clear();
            list.add(loader);
        })));
    }

    /**
     * Validate data against a schema; one problem per violation, field = the dotted location. A
     * schema that refers outside itself ({@link #remoteRef}) validates nothing: one
     * {@code schema.remoteRef} problem.
     */
    public static List<Problem> validate(JsonNode schema, JsonNode data) {
        return validate(FACTORY, schema, data);
    }

    static List<Problem> validate(JsonSchemaFactory factory, JsonNode schema, JsonNode data) {
        List<Problem> problems = new ArrayList<>();
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return problems;
        }
        Optional<RemoteRef> remote = remoteRef(schema);
        if (remote.isPresent()) {
            problems.add(Problem.error("schema.remoteRef", null, null, remote.get().describe("The schema")));
            return problems;
        }
        JsonSchema js = factory.getSchema(schema);
        Set<ValidationMessage> messages = js.validate(data == null ? F.objectNode() : data);
        for (ValidationMessage m : messages) {
            String field = m.getInstanceLocation() == null ? null : m.getInstanceLocation().toString();
            if (field != null && field.startsWith("$.")) {
                field = field.substring(2);
            } else if ("$".equals(field)) {
                field = null;
            }
            problems.add(Problem.error("schema", null, field, m.getMessage().replaceFirst("^\\$\\.?", "").trim()));
        }
        return problems;
    }

    public static boolean isValid(JsonNode schema, JsonNode data) {
        return validate(schema, data).isEmpty();
    }

    // ------------------------------------------------------------------ is it a schema

    /** A violation: where (a JSON pointer, {@code ""} for the root) and the validator's message. */
    public record Violation(String pointer, String message) {
    }

    /** A reference out of a schema: where it is (a JSON pointer into the schema) and the IRI it names. */
    public record RemoteRef(String pointer, String ref) {

        /** {@code what} refers to the IRI, said for people; never fetched. */
        public String describe(String what) {
            return what + " refers to " + ref + ", outside itself: only references into the same schema (#/...) resolve, and nothing is fetched";
        }
    }

    /** A schema compiled once, to validate many documents against (the Workflow file's schema). */
    public static final class Compiled {

        private final JsonSchema schema;

        private Compiled(JsonSchema schema) {
            this.schema = schema;
        }

        /**
         * Every violation, sorted by pointer then message. An unknown property is located at the
         * property ({@code /metadata/foo}); the message carries the location of the object it sits in.
         */
        public List<Violation> violations(JsonNode data) {
            Set<Violation> out = new LinkedHashSet<>();
            for (ValidationMessage m : schema.validate(data == null ? F.objectNode() : data)) {
                String at = m.getInstanceLocation().toString();
                String pointer = "additionalProperties".equals(m.getType()) && m.getProperty() != null ? at + "/" + escape(m.getProperty()) : at;
                out.add(new Violation(pointer, at.isEmpty() ? m.getError() : at + ": " + m.getError()));
            }
            return out.stream().sorted(Comparator.comparing(Violation::pointer).thenComparing(Violation::message)).toList();
        }
    }

    /**
     * Compile a schema once, refusing (400) one that refers outside itself; violations are
     * located by JSON pointers.
     */
    public static Compiled compile(JsonNode schema) {
        remoteRef(schema).ifPresent(r -> {
            throw new IllegalArgumentException(r.describe("The schema"));
        });
        JsonSchema js = FACTORY.getSchema(schema, POINTERS);
        js.initializeValidators();
        return new Compiled(js);
    }

    /**
     * The first reference out of a schema: a {@code $ref} (or {@code $dynamicRef},
     * {@code $recursiveRef}) that is not a {@code #} pointer into the same document. Values of
     * data keywords ({@code enum}, {@code const}, {@code default}, {@code examples}) are not looked into.
     */
    public static Optional<RemoteRef> remoteRef(JsonNode schema) {
        return remoteRef(schema, "", false, 0);
    }

    private static Optional<RemoteRef> remoteRef(JsonNode node, String pointer, boolean names, int depth) {
        if (node == null || depth > 64) {
            return Optional.empty();
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                Optional<RemoteRef> r = remoteRef(node.get(i), pointer + "/" + i, false, depth + 1);
                if (r.isPresent()) {
                    return r;
                }
            }
            return Optional.empty();
        }
        if (!node.isObject()) {
            return Optional.empty();
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String key = e.getKey();
            JsonNode v = e.getValue();
            String at = pointer + "/" + escape(key);
            Optional<RemoteRef> r = Optional.empty();
            if (names) {
                r = remoteRef(v, at, false, depth + 1);
            } else if (REF_KEYWORDS.contains(key)) {
                if (v.isTextual() && !v.asText().startsWith("#")) {
                    return Optional.of(new RemoteRef(at, v.asText()));
                }
            } else if (!DATA_KEYWORDS.contains(key)) {
                r = remoteRef(v, at, SCHEMA_MAPS.contains(key), depth + 1);
            }
            if (r.isPresent()) {
                return r;
            }
        }
        return Optional.empty();
    }

    /**
     * Why a schema is not one the validator can use, found without loading anything from outside
     * it: its violations of the meta-schema it names ({@code $schema}; 2020-12 when it names none),
     * one per location; else a pattern that does not compile or a {@code #} reference to nothing;
     * else the reason the validator does not compile it. Empty for a usable schema. A reference out
     * of the schema is {@link #remoteRef}'s finding: rule it out first, it does not compile here.
     */
    public static List<Violation> metaViolations(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return List.of();
        }
        String dialect = schema.path("$schema").isTextual() ? schema.get("$schema").asText() : SchemaId.V202012;
        try {
            List<Violation> found = onePerLocation(FACTORY.getSchema(SchemaLocation.of(dialect), POINTERS).validate(schema));
            if (found.isEmpty()) {
                found = localViolations(schema);
            }
            if (found.isEmpty()) {
                FACTORY.getSchema(schema).initializeValidators();
            }
            return found;
        } catch (RuntimeException e) {
            return List.of(new Violation("", reason(e)));
        }
    }

    /** One message per location; a type mismatch (which an alternative of the meta-schema reports) gives way to any other. */
    private static List<Violation> onePerLocation(Set<ValidationMessage> messages) {
        Map<String, ValidationMessage> best = new TreeMap<>();
        for (ValidationMessage m : messages) {
            best.merge(m.getInstanceLocation().toString(), m, Schemas::preferred);
        }
        return best.entrySet().stream().map(e -> new Violation(e.getKey(), e.getValue().getError())).toList();
    }

    private static ValidationMessage preferred(ValidationMessage a, ValidationMessage b) {
        boolean ta = "type".equals(a.getType());
        boolean tb = "type".equals(b.getType());
        if (ta != tb) {
            return ta ? b : a;
        }
        return a.getError().compareTo(b.getError()) <= 0 ? a : b;
    }

    /** Patterns that do not compile and {@code #} references that point at nothing, in document order. */
    private static List<Violation> localViolations(JsonNode schema) {
        List<Violation> out = new ArrayList<>();
        boolean scoped = hasNestedId(schema, 0);
        walk(schema, "", false, 0, (node, pointer) -> {
            JsonNode pattern = node.get("pattern");
            if (pattern != null && pattern.isTextual()) {
                compilePattern(pattern.asText(), pointer + "/pattern", out);
            }
            JsonNode patternProperties = node.get("patternProperties");
            if (patternProperties != null && patternProperties.isObject()) {
                patternProperties.fieldNames().forEachRemaining(p -> compilePattern(p, pointer + "/patternProperties/" + escape(p), out));
            }
            JsonNode ref = node.get("$ref");
            if (!scoped && ref != null && ref.isTextual() && ref.asText().startsWith("#") && !resolves(schema, ref.asText())) {
                out.add(new Violation(pointer + "/$ref", "Reference " + ref.asText() + " cannot be resolved"));
            }
        });
        return out;
    }

    private interface SchemaVisitor {
        void visit(JsonNode schema, String pointer);
    }

    /** Visit every subschema (objects in schema positions), skipping data keywords and map names. */
    private static void walk(JsonNode node, String pointer, boolean names, int depth, SchemaVisitor visitor) {
        if (node == null || depth > 64) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), pointer + "/" + i, false, depth + 1, visitor);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (!names) {
            visitor.visit(node, pointer);
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String key = e.getKey();
            String at = pointer + "/" + escape(key);
            if (names) {
                walk(e.getValue(), at, false, depth + 1, visitor);
            } else if (!DATA_KEYWORDS.contains(key) && !REF_KEYWORDS.contains(key)) {
                walk(e.getValue(), at, SCHEMA_MAPS.contains(key), depth + 1, visitor);
            }
        }
    }

    private static void compilePattern(String regex, String pointer, List<Violation> out) {
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            out.add(new Violation(pointer, "pattern does not compile: " + e.getDescription() + " near index " + e.getIndex()));
        }
    }

    /** A nested {@code $id} moves where {@code #} references resolve; such schemas are left to the validator. */
    private static boolean hasNestedId(JsonNode schema, int depth) {
        boolean[] found = {false};
        walk(schema, "", false, depth, (node, pointer) -> found[0] |= !pointer.isEmpty() && node.has("$id"));
        return found[0];
    }

    /** Whether a {@code #} reference points into the schema: {@code #}, a JSON pointer {@code #/...} or an anchor {@code #name}. */
    private static boolean resolves(JsonNode root, String ref) {
        String fragment = URLDecoder.decode(ref.substring(1).replace("+", "%2B"), StandardCharsets.UTF_8);
        if (fragment.isEmpty()) {
            return true;
        }
        if (!fragment.startsWith("/")) {
            boolean[] found = {false};
            walk(root, "", false, 0, (node, pointer) -> found[0] |= fragment.equals(node.path("$anchor").asText(null))
                    || fragment.equals(node.path("$dynamicAnchor").asText(null)));
            return found[0];
        }
        JsonNode at = root;
        for (String segment : fragment.substring(1).split("/", -1)) {
            String key = segment.replace("~1", "/").replace("~0", "~");
            if (at.isArray() && key.matches("\\d+")) {
                at = at.path(Integer.parseInt(key));
            } else {
                at = at.path(key);
            }
            if (at.isMissingNode()) {
                return false;
            }
        }
        return true;
    }

    /** The validator's reason, first line, without the exception class it wraps. */
    private static String reason(RuntimeException e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        int nl = m.indexOf('\n');
        m = nl < 0 ? m : m.substring(0, nl);
        return m.replaceFirst("^[\\w.$]+(Exception|Error): ", "").replaceFirst("^: ", "").trim();
    }

    /** A JSON pointer segment: {@code ~} and {@code /} escaped. */
    static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    /** A JSON pointer as a dotted suffix: {@code /properties/vmId/type} is {@code .properties.vmId.type}, the root is empty. */
    public static String dotted(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String segment : pointer.substring(1).split("/", -1)) {
            out.append('.').append(segment.replace("~1", "/").replace("~0", "~"));
        }
        return out.toString();
    }

    /** Infer a schema from an example value (the HTTP-without-a-spec and overrides paths). */
    public static ObjectNode infer(JsonNode example) {
        ObjectNode s = F.objectNode();
        if (example == null || example.isNull()) {
            s.put("type", "null");
            return s;
        }
        if (example.isObject()) {
            s.put("type", "object");
            ObjectNode props = F.objectNode();
            example.properties().forEach(e -> props.set(e.getKey(), infer(e.getValue())));
            s.set("properties", props);
            return s;
        }
        if (example.isArray()) {
            s.put("type", "array");
            if (example.size() > 0) {
                s.set("items", infer(example.get(0)));
            }
            return s;
        }
        if (example.isBoolean()) {
            s.put("type", "boolean");
        } else if (example.isIntegralNumber()) {
            s.put("type", "integer");
        } else if (example.isNumber()) {
            s.put("type", "number");
        } else {
            s.put("type", "string");
        }
        return s;
    }

    /**
     * Every dotted leaf (and container) path a schema describes: {@code vm.id}, {@code vm.name},
     * {@code items}. Used by the did-you-mean check and the mapping panel.
     */
    public static List<String> paths(JsonNode schema) {
        List<String> out = new ArrayList<>();
        collectPaths(schema, "", out, 0);
        return out;
    }

    private static void collectPaths(JsonNode schema, String prefix, List<String> out, int depth) {
        if (schema == null || !schema.isObject() || depth > 8) {
            return;
        }
        JsonNode props = schema.get("properties");
        if (props != null && props.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = props.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String p = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                out.add(p);
                collectPaths(e.getValue(), p, out, depth + 1);
            }
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            collectPaths(items, prefix.isEmpty() ? "[]" : prefix + "[]", out, depth + 1);
        }
    }

    /** Every dotted path present in an example value (same shape as {@link #paths}). */
    public static List<String> pathsOf(JsonNode example) {
        List<String> out = new ArrayList<>();
        collectValuePaths(example, "", out, 0);
        return out;
    }

    private static void collectValuePaths(JsonNode v, String prefix, List<String> out, int depth) {
        if (v == null || depth > 8) {
            return;
        }
        if (v.isObject()) {
            v.properties().forEach(e -> {
                String p = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                out.add(p);
                collectValuePaths(e.getValue(), p, out, depth + 1);
            });
        } else if (v.isArray() && v.size() > 0) {
            collectValuePaths(v.get(0), prefix.isEmpty() ? "[]" : prefix + "[]", out, depth + 1);
        }
    }

    /**
     * Compare a real response with a schema: fields the response has that the schema lacks, and
     * fields whose type differs. Powers "Use the real response" in the wizard.
     */
    public static List<Mismatch> mismatches(JsonNode schema, JsonNode response) {
        List<Mismatch> out = new ArrayList<>();
        compare(schema, response, "", out, 0);
        return out;
    }

    public record Mismatch(String field, String expected, String actual) {
    }

    private static void compare(JsonNode schema, JsonNode value, String prefix, List<Mismatch> out, int depth) {
        if (value == null || depth > 8) {
            return;
        }
        String actual = typeOf(value);
        if (schema == null || !schema.isObject() || schema.isEmpty()) {
            if (!prefix.isEmpty()) {
                out.add(new Mismatch(prefix, "missing", actual));
            }
            return;
        }
        String expected = schema.path("type").asText(null);
        if (expected != null && !expected.equals(actual) && !("number".equals(expected) && "integer".equals(actual))
                && !value.isNull()) {
            out.add(new Mismatch(prefix.isEmpty() ? "$" : prefix, expected, actual));
            return;
        }
        if (value.isObject()) {
            JsonNode props = schema.path("properties");
            value.properties().forEach(e -> {
                String p = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                JsonNode sub = props.get(e.getKey());
                if (sub == null) {
                    out.add(new Mismatch(p, "missing", typeOf(e.getValue())));
                } else {
                    compare(sub, e.getValue(), p, out, depth + 1);
                }
            });
        } else if (value.isArray() && value.size() > 0) {
            compare(schema.get("items"), value.get(0), prefix + "[]", out, depth + 1);
        }
    }

    private static String typeOf(JsonNode v) {
        if (v.isObject()) {
            return "object";
        }
        if (v.isArray()) {
            return "array";
        }
        if (v.isBoolean()) {
            return "boolean";
        }
        if (v.isIntegralNumber()) {
            return "integer";
        }
        if (v.isNumber()) {
            return "number";
        }
        if (v.isNull()) {
            return "null";
        }
        return "string";
    }

    /** Deep-merge an override block into a schema (overrides win, nothing is removed). */
    public static ObjectNode merge(JsonNode schema, JsonNode override) {
        ObjectNode out = schema != null && schema.isObject() ? schema.deepCopy() : F.objectNode();
        if (override == null || !override.isObject()) {
            return out;
        }
        override.properties().forEach(e -> {
            JsonNode existing = out.get(e.getKey());
            if (existing != null && existing.isObject() && e.getValue().isObject()) {
                out.set(e.getKey(), merge(existing, e.getValue()));
            } else {
                out.set(e.getKey(), e.getValue());
            }
        });
        return out;
    }

    /** A sample value that satisfies a schema, for forms, mocks and dry runs. */
    public static JsonNode sample(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return F.nullNode();
        }
        if (schema.has("example")) {
            return schema.get("example");
        }
        if (schema.has("default")) {
            return schema.get("default");
        }
        if (schema.has("enum") && schema.get("enum").size() > 0) {
            return schema.get("enum").get(0);
        }
        String type = schema.path("type").asText("object");
        return switch (type) {
            case "object" -> {
                ObjectNode o = F.objectNode();
                JsonNode props = schema.path("properties");
                props.properties().forEach(e -> o.set(e.getKey(), sample(e.getValue())));
                yield o;
            }
            case "array" -> {
                ArrayNode a = F.arrayNode();
                if (schema.has("items")) {
                    a.add(sample(schema.get("items")));
                }
                yield a;
            }
            case "integer" -> F.numberNode(1);
            case "number" -> F.numberNode(1.0);
            case "boolean" -> F.booleanNode(true);
            case "null" -> F.nullNode();
            default -> F.textNode(schema.path("format").asText("string").equals("date-time") ? "2026-01-01T00:00:00Z" : "string");
        };
    }

    /** Ordered copy of the top-level properties, for forms. */
    public static Map<String, JsonNode> properties(JsonNode schema) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (schema != null) {
            schema.path("properties").properties().forEach(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }

    public static ObjectNode object() {
        return F.objectNode();
    }
}

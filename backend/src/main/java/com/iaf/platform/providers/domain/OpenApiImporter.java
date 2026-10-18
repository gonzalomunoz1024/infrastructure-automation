package com.iaf.platform.providers.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.providers.domain.SpecImport.AuthInfo;
import com.iaf.platform.providers.domain.SpecImport.Operation;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/**
 * Reads an OpenAPI 3.x document (JSON or YAML) into what the Add provider wizard shows: title,
 * version, servers, the auth scheme, and one operation per path and method with its effect, retry
 * safety and resolved input and output schemas. Pure: the document is given as text.
 *
 * <p>Effects: GET reads, DELETE deletes, PUT and PATCH change, POST creates unless its
 * operationId or summary says otherwise (get/list/search/find read; restart/update/stop/start/resize
 * change; delete/destroy/remove delete). Retries are safe for GET, PUT and DELETE.</p>
 */
public final class OpenApiImporter {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options");
    private static final Set<String> READ_WORDS = Set.of("get", "list", "search", "find", "describe", "read", "fetch", "query", "lookup");
    private static final Set<String> CHANGE_WORDS = Set.of("restart", "update", "stop", "start", "resize", "reboot", "modify", "upgrade",
            "scale", "attach", "detach", "enable", "disable", "rename", "move", "set", "power", "resume", "pause", "suspend");
    private static final Set<String> DELETE_WORDS = Set.of("delete", "destroy", "remove", "purge");
    private static final int MAX_DEPTH = 24;
    private static final int REF_DEPTH = 10;

    private OpenApiImporter() {
    }

    /** Where an operation lives in a document, with its parameters (path and operation level) resolved. */
    public record Located(String method, String path, JsonNode operation, List<JsonNode> parameters) {
        public List<String> parameterNames(String in) {
            return parameters.stream().filter(p -> in.equals(p.path("in").asText())).map(p -> p.path("name").asText()).toList();
        }
    }

    /** Parse JSON or YAML text into a document; only OpenAPI 3.x is accepted. */
    public static JsonNode read(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("The OpenAPI document is empty");
        }
        // the strict reader every loader uses: a key written twice is refused, never silently kept once
        JsonNode doc = WorkflowYaml.readTree(raw);
        if (doc == null || !doc.isObject()) {
            throw new IllegalArgumentException("Not an OpenAPI document: expected a mapping with openapi, info and paths");
        }
        String version = doc.path("openapi").asText(null);
        if (version == null) {
            throw new IllegalArgumentException(doc.has("swagger") ? "Swagger 2.0 documents are not supported; convert the spec to OpenAPI 3.x"
                    : "Not an OpenAPI document: the openapi field is missing");
        }
        if (!version.startsWith("3.")) {
            throw new IllegalArgumentException("OpenAPI " + version + " is not supported; the platform reads 3.x");
        }
        if (!doc.path("paths").isObject()) {
            throw new IllegalArgumentException("The OpenAPI document has no paths");
        }
        return doc;
    }

    public static SpecImport parse(String raw) {
        JsonNode doc = read(raw);
        JsonNode info = doc.path("info");
        List<String> servers = new ArrayList<>();
        for (JsonNode s : doc.path("servers")) {
            String url = s.path("url").asText(null);
            if (url != null && !url.isBlank()) {
                servers.add(url);
            }
        }
        List<Operation> operations = new ArrayList<>();
        for (Located l : operations(doc)) {
            JsonNode op = l.operation();
            String id = op.path("operationId").asText(null);
            if (id == null || id.isBlank()) {
                id = operationId(l.method(), l.path());
            }
            String summary = op.path("summary").asText(null);
            if (summary == null || summary.isBlank()) {
                summary = op.path("description").asText(null);
            }
            Effect effect = inferEffect(l.method(), id, summary);
            operations.add(new Operation(id, l.method(), l.path(), summary, effect, retry(l.method()), inputSchema(doc, l), outputSchema(doc, op)));
        }
        return new SpecImport(info.path("title").asText(null), info.path("version").asText(null), WorkflowYaml.sha256(raw), auth(doc), servers, operations);
    }

    /** Every operation of the document, in document order. */
    public static List<Located> operations(JsonNode doc) {
        List<Located> out = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> paths = doc.path("paths").properties().iterator();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> p = paths.next();
            if (p.getKey().startsWith("x-") || !p.getValue().isObject()) {
                continue;
            }
            JsonNode item = resolve(p.getValue(), doc);
            List<JsonNode> pathParams = parameters(doc, item.get("parameters"));
            for (String method : METHODS) {
                JsonNode op = item.get(method);
                if (op == null || !op.isObject()) {
                    continue;
                }
                List<JsonNode> params = new ArrayList<>(parameters(doc, op.get("parameters")));
                for (JsonNode pp : pathParams) {
                    boolean overridden = params.stream().anyMatch(q -> q.path("name").asText().equals(pp.path("name").asText())
                            && q.path("in").asText().equals(pp.path("in").asText()));
                    if (!overridden) {
                        params.add(pp);
                    }
                }
                out.add(new Located(method.toUpperCase(Locale.ROOT), p.getKey(), op, params));
            }
        }
        return out;
    }

    /** The operation with this operationId (as written, or as {@link #operationId} would generate it). */
    public static Optional<Located> find(JsonNode doc, String operationId) {
        if (operationId == null) {
            return Optional.empty();
        }
        List<Located> all = operations(doc);
        Optional<Located> named = all.stream().filter(l -> operationId.equals(l.operation().path("operationId").asText(null))).findFirst();
        if (named.isPresent()) {
            return named;
        }
        return all.stream().filter(l -> operationId.equals(operationId(l.method(), l.path()))).findFirst();
    }

    /** {@code get_vms_id} for {@code GET /vms/{id}}: the id generated when the spec has none. */
    public static String operationId(String method, String path) {
        String cleaned = path == null ? "" : path.replaceAll("[{}]", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("(^_+|_+$)", "");
        return method.toLowerCase(Locale.ROOT) + (cleaned.isEmpty() ? "" : "_" + cleaned);
    }

    public static Effect inferEffect(String method, String operationId, String summary) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (!"POST".equals(m)) {
            return Effect.ofHttpMethod(m);
        }
        Set<String> words = new LinkedHashSet<>(words(operationId));
        words.addAll(words(summary));
        if (words.stream().anyMatch(DELETE_WORDS::contains)) {
            return Effect.DELETE;
        }
        if (words.stream().anyMatch(READ_WORDS::contains)) {
            return Effect.READ;
        }
        if (words.stream().anyMatch(CHANGE_WORDS::contains)) {
            return Effect.CHANGE;
        }
        return Effect.CREATE;
    }

    public static String retry(String method) {
        return switch (method == null ? "" : method.toUpperCase(Locale.ROOT)) {
            case "GET", "PUT", "DELETE", "HEAD", "OPTIONS" -> "safe";
            default -> "unsafe";
        };
    }

    static List<String> words(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String spaced = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return Arrays.stream(spaced.split("[^a-z0-9]+")).filter(w -> !w.isEmpty()).toList();
    }

    // ---------------------------------------------------------------- auth

    static AuthInfo auth(JsonNode doc) {
        JsonNode schemes = doc.path("components").path("securitySchemes");
        if (!schemes.isObject() || schemes.isEmpty()) {
            return new AuthInfo("none", null);
        }
        List<String> preferred = new ArrayList<>();
        for (JsonNode req : doc.path("security")) {
            req.fieldNames().forEachRemaining(preferred::add);
        }
        schemes.fieldNames().forEachRemaining(preferred::add);
        for (String name : preferred) {
            JsonNode s = schemes.get(name);
            if (s == null || !s.isObject()) {
                continue;
            }
            String type = s.path("type").asText("");
            String scheme = s.path("scheme").asText("").toLowerCase(Locale.ROOT);
            if ("http".equals(type) && "bearer".equals(scheme)) {
                return new AuthInfo("bearer", null);
            }
            if ("http".equals(type) && "basic".equals(scheme)) {
                return new AuthInfo("basic", null);
            }
            if ("apiKey".equals(type) && "header".equals(s.path("in").asText(""))) {
                return new AuthInfo("header", s.path("name").asText(null));
            }
            if ("oauth2".equals(type) || "openIdConnect".equals(type)) {
                return new AuthInfo("bearer", null);
            }
        }
        return new AuthInfo("none", null);
    }

    // ---------------------------------------------------------------- schemas

    static List<JsonNode> parameters(JsonNode doc, JsonNode list) {
        List<JsonNode> out = new ArrayList<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode p : list) {
            JsonNode r = resolve(p, doc);
            if (r.isObject() && r.has("name")) {
                out.add(r);
            }
        }
        return out;
    }

    /** Path and query parameters plus the JSON request body, as one object schema. */
    public static ObjectNode inputSchema(JsonNode doc, Located l) {
        ObjectNode schema = F.objectNode();
        schema.put("type", "object");
        ObjectNode props = F.objectNode();
        LinkedHashSet<String> required = new LinkedHashSet<>();
        for (JsonNode p : l.parameters()) {
            String in = p.path("in").asText("");
            if (!"path".equals(in) && !"query".equals(in)) {
                continue;
            }
            String name = p.path("name").asText();
            ObjectNode ps = p.has("schema") && p.get("schema").isObject() ? resolve(p.get("schema"), doc).deepCopy() : F.objectNode();
            if (!ps.has("type")) {
                ps.put("type", "string");
            }
            if (p.hasNonNull("description") && !ps.has("description")) {
                ps.put("description", p.get("description").asText());
            }
            ps.put("x-in", in);
            props.set(name, ps);
            if ("path".equals(in) || p.path("required").asBoolean(false)) {
                required.add(name);
            }
        }
        JsonNode body = resolve(l.operation().get("requestBody"), doc);
        if (body != null && body.isObject()) {
            JsonNode bodySchema = content(doc, body.get("content"));
            if (bodySchema != null) {
                if ("object".equals(bodySchema.path("type").asText("object")) && bodySchema.path("properties").isObject()) {
                    bodySchema.path("properties").properties().forEach(e -> props.set(e.getKey(), e.getValue()));
                    for (JsonNode r : bodySchema.path("required")) {
                        required.add(r.asText());
                    }
                } else {
                    props.set("body", bodySchema);
                    if (body.path("required").asBoolean(false)) {
                        required.add("body");
                    }
                }
            }
        }
        if (!required.isEmpty()) {
            ArrayNode req = schema.putArray("required");
            required.forEach(req::add);
        }
        schema.set("properties", props);
        return schema;
    }

    /** The schema of the first 2xx response, resolved; null when the operation returns nothing. */
    public static JsonNode outputSchema(JsonNode doc, JsonNode operation) {
        JsonNode responses = operation.path("responses");
        if (!responses.isObject()) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> it = responses.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String code = e.getKey().toUpperCase(Locale.ROOT);
            if (!code.startsWith("2")) {
                continue;
            }
            JsonNode response = resolve(e.getValue(), doc);
            JsonNode schema = content(doc, response.get("content"));
            if (schema != null) {
                return schema;
            }
        }
        return null;
    }

    /** The resolved schema of the JSON media type of a content map, or of the first media type. */
    static JsonNode content(JsonNode doc, JsonNode content) {
        if (content == null || !content.isObject() || content.isEmpty()) {
            return null;
        }
        JsonNode media = content.get("application/json");
        if (media == null) {
            Iterator<Map.Entry<String, JsonNode>> it = content.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().contains("json")) {
                    media = e.getValue();
                    break;
                }
            }
            if (media == null) {
                media = content.properties().iterator().next().getValue();
            }
        }
        JsonNode schema = media.get("schema");
        return schema == null ? null : resolve(schema, doc);
    }

    /** Resolve local {@code $ref}s (recursively, with cycle protection) and flatten {@code allOf} of objects. */
    public static JsonNode resolve(JsonNode node, JsonNode doc) {
        return resolve(node, doc, 0, 0);
    }

    private static JsonNode resolve(JsonNode node, JsonNode doc, int depth, int refs) {
        if (node == null || !node.isObject() || depth > MAX_DEPTH) {
            return node;
        }
        ObjectNode out;
        JsonNode ref = node.get("$ref");
        if (ref != null && ref.isTextual()) {
            JsonNode target = pointer(doc, ref.asText());
            if (target == null || !target.isObject() || refs >= REF_DEPTH) {
                out = F.objectNode();
                out.put("type", "object");
                if (target == null) {
                    out.put("description", "Unresolved reference " + ref.asText());
                }
                return out;
            }
            JsonNode resolved = resolve(target, doc, depth + 1, refs + 1);
            out = resolved.isObject() ? ((ObjectNode) resolved).deepCopy() : F.objectNode();
            node.properties().forEach(e -> {
                if (!"$ref".equals(e.getKey())) {
                    out.set(e.getKey(), e.getValue());
                }
            });
        } else {
            out = node.deepCopy();
        }
        JsonNode props = out.get("properties");
        if (props != null && props.isObject()) {
            ObjectNode np = F.objectNode();
            props.properties().forEach(e -> np.set(e.getKey(), resolve(e.getValue(), doc, depth + 1, refs)));
            out.set("properties", np);
        }
        for (String key : List.of("items", "additionalProperties", "not")) {
            JsonNode v = out.get(key);
            if (v != null && v.isObject()) {
                out.set(key, resolve(v, doc, depth + 1, refs));
            }
        }
        for (String key : List.of("allOf", "oneOf", "anyOf")) {
            JsonNode v = out.get(key);
            if (v != null && v.isArray()) {
                ArrayNode na = F.arrayNode();
                v.forEach(item -> na.add(resolve(item, doc, depth + 1, refs)));
                out.set(key, na);
            }
        }
        flattenAllOf(out);
        return out;
    }

    private static void flattenAllOf(ObjectNode out) {
        JsonNode allOf = out.get("allOf");
        if (allOf == null || !allOf.isArray() || allOf.isEmpty()) {
            return;
        }
        for (JsonNode part : allOf) {
            boolean objectLike = part.isObject() && ("object".equals(part.path("type").asText("object")) || part.has("properties"));
            if (!objectLike) {
                return;
            }
        }
        ObjectNode props = out.has("properties") && out.get("properties").isObject() ? (ObjectNode) out.get("properties") : F.objectNode();
        LinkedHashSet<String> required = new LinkedHashSet<>();
        out.path("required").forEach(r -> required.add(r.asText()));
        for (JsonNode part : allOf) {
            part.path("properties").properties().forEach(e -> props.set(e.getKey(), e.getValue()));
            part.path("required").forEach(r -> required.add(r.asText()));
        }
        out.remove("allOf");
        out.put("type", "object");
        out.set("properties", props);
        if (!required.isEmpty()) {
            ArrayNode req = out.putArray("required");
            required.forEach(req::add);
        }
    }

    /** A local JSON pointer like {@code #/components/schemas/Vm}; null when it points nowhere. */
    static JsonNode pointer(JsonNode doc, String ref) {
        if (ref == null || !ref.startsWith("#/")) {
            return null;
        }
        JsonNode current = doc;
        for (String raw : ref.substring(2).split("/")) {
            String key = raw.replace("~1", "/").replace("~0", "~");
            if (current == null) {
                return null;
            }
            // an index has at most 9 digits, so a crafted segment never overflows: a longer one points nowhere
            current = current.isArray() && key.length() <= 9 && key.matches("\\d+") ? current.get(Integer.parseInt(key)) : current.get(key);
        }
        return current;
    }

    /** The resolved operations of a document keyed by operationId, for the HTTP adapter. */
    public static Map<String, Located> index(JsonNode doc) {
        Map<String, Located> out = new LinkedHashMap<>();
        for (Located l : operations(doc)) {
            String id = l.operation().path("operationId").asText(null);
            out.putIfAbsent(id == null || id.isBlank() ? operationId(l.method(), l.path()) : id, l);
        }
        return out;
    }
}

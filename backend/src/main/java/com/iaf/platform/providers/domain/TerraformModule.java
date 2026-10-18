package com.iaf.platform.providers.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.schema.Schemas;

/**
 * A Terraform module's interface, read from the {@code .tf} and {@code .tf.json} files of its
 * folder: its variables become the inputs of a Plan and an Apply action, its outputs what Apply
 * returns. A sensitive variable (or one named like a secret) is never an input: its value would
 * travel in every job's input. Pure: the files come from the caller.
 */
public final class TerraformModule {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** {@code contains(["a", "b"], var.size)} in a validation: the variable takes one of those values. */
    private static final String ENUM_CONDITION = "contains\\(\\s*(\\[[^\\]]*\\])\\s*,\\s*var\\.%s\\s*\\)";
    /** Longer conditions are not searched for an enum: the search is quadratic in the worst case, and real ones are short. */
    private static final int MAX_CONDITION = 2000;

    private TerraformModule() {
    }

    /** {@code type} is the constraint as written ({@code list(string)}); {@code file} where the variable is declared. */
    public record Variable(String name, String type, boolean required, boolean sensitive, String description,
            @JsonProperty("default") JsonNode defaultValue, String file, @JsonIgnore ObjectNode schema) {

        /** Never an input: Terraform marks it sensitive, or its name says it holds a secret. */
        @JsonProperty("secret")
        public boolean secret() {
            return sensitive || ProviderChecks.secretKey(name);
        }
    }

    public record Output(String name, String description, boolean sensitive, String file) {
    }

    /** The variables and outputs in declaration order; {@code notes} name the files that could not be read. */
    public record Interface(List<Variable> variables, List<Output> outputs, List<String> notes) {
    }

    /** What goes in the draft provider file besides the module's interface. */
    public record Draft(String name, String title, String owner, String version, String description, String source, String moduleVersion,
            String commit, String credential) {
    }

    /** A module in a Git repository: the folder {@code dir} ("" for the root) at {@code ref} (null for the default branch). */
    public record GitLocation(String repo, String dir, String ref) {
    }

    /** True for the files Terraform reads as configuration. */
    public static boolean configFile(String path) {
        return path.endsWith(".tf") || path.endsWith(".tf.json");
    }

    /**
     * The Git repository behind a module source as Terraform writes it ({@code git::https://host/repo//dir?ref=v1},
     * {@code github.com/org/repo//dir?ref=v1}), or null when the source is not Git (an archive, a bucket).
     */
    public static GitLocation gitSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String s = source.trim();
        boolean forced = s.startsWith("git::");
        if (forced) {
            s = s.substring("git::".length());
        } else if (!s.matches("^(https://)?(github\\.com|bitbucket\\.org)/.+")) {
            return null;
        }
        String ref = null;
        int q = s.indexOf('?');
        if (q >= 0) {
            for (String param : s.substring(q + 1).split("&")) {
                if (param.startsWith("ref=")) {
                    ref = java.net.URLDecoder.decode(param.substring("ref=".length()), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            s = s.substring(0, q);
        }
        int scheme = s.indexOf("://");
        int from = scheme < 0 ? 0 : scheme + 3;
        int split = s.indexOf("//", from);
        String dir = "";
        if (split >= 0) {
            dir = RepoPaths.trimSlashes(s.substring(split + 2));
            s = s.substring(0, split);
        }
        if (scheme < 0 && !s.startsWith("git@")) {
            s = "https://" + s;
        }
        return new GitLocation(s, dir, ref == null || ref.isBlank() ? null : ref);
    }

    /**
     * The interface a registry describes ({@code root.inputs}, {@code root.outputs}), for modules
     * whose code is an archive. A default arrives JSON-encoded, as the public registry sends it.
     */
    public static Interface fromRegistry(JsonNode details) {
        List<Variable> variables = new ArrayList<>();
        List<Output> outputs = new ArrayList<>();
        JsonNode root = details == null ? F.missingNode() : details.path("root");
        for (JsonNode in : root.path("inputs")) {
            String name = in.path("name").asText(null);
            if (name == null) {
                continue;
            }
            String typeText = in.path("type").isTextual() && !in.path("type").asText().isBlank() ? in.path("type").asText() : null;
            Hcl.Type type = typeText == null ? null : Hcl.type(Hcl.expression(typeText));
            boolean required = in.path("required").asBoolean(!in.has("default"));
            JsonNode defaultValue = null;
            if (!required && in.has("default")) {
                JsonNode raw = in.get("default");
                defaultValue = raw;
                if (raw.isTextual()) {
                    try {
                        defaultValue = JSON.readTree(raw.asText());
                    } catch (JsonProcessingException e) {
                        defaultValue = raw;
                    }
                    if (defaultValue == null || defaultValue.isMissingNode()) {
                        defaultValue = null;
                    }
                }
            }
            variables.add(variable(name, typeText, type, !required, defaultValue, in.path("sensitive").asBoolean(false),
                    in.path("description").isTextual() ? in.path("description").asText() : null, List.of(), "registry"));
        }
        for (JsonNode out : root.path("outputs")) {
            String name = out.path("name").asText(null);
            if (name != null) {
                outputs.add(new Output(name, out.path("description").isTextual() ? out.path("description").asText() : null,
                        out.path("sensitive").asBoolean(false), "registry"));
            }
        }
        return new Interface(List.copyOf(variables), List.copyOf(outputs), List.of());
    }

    /** Read every variable and output block of the files ({@code path -> text}), in path order. */
    public static Interface read(Map<String, String> files) {
        List<Variable> variables = new ArrayList<>();
        List<Output> outputs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        files.forEach((path, text) -> {
            if (!configFile(path)) {
                return;
            }
            try {
                if (path.endsWith(".tf.json")) {
                    readJson(path, text, variables, outputs);
                } else {
                    readHcl(path, text, variables, outputs);
                }
            } catch (IllegalArgumentException e) {
                notes.add(path + " could not be read (" + e.getMessage() + "); the variables it declares are missing");
            }
        });
        return new Interface(List.copyOf(variables), List.copyOf(outputs), List.copyOf(notes));
    }

    private static void readHcl(String path, String text, List<Variable> variables, List<Output> outputs) {
        for (Hcl.Block b : Hcl.parse(text)) {
            if ("variable".equals(b.type()) && b.label() != null) {
                Hcl.Expr type = b.attribute("type");
                Hcl.Expr def = b.attribute("default");
                JsonNode defaultValue = Hcl.literal(def);
                List<String> conditions = new ArrayList<>();
                for (Hcl.Block v : b.blocks("validation")) {
                    if (v.attribute("condition") != null) {
                        conditions.add(v.attribute("condition").source());
                    }
                }
                variables.add(variable(b.label(), type == null ? null : type.text(), Hcl.type(type), def != null, defaultValue,
                        Hcl.bool(b.attribute("sensitive"), false), Hcl.string(b.attribute("description")), conditions, path));
            } else if ("output".equals(b.type()) && b.label() != null) {
                outputs.add(new Output(b.label(), Hcl.string(b.attribute("description")), Hcl.bool(b.attribute("sensitive"), false), path));
            }
        }
    }

    private static void readJson(String path, String text, List<Variable> variables, List<Output> outputs) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getOriginalMessage());
        }
        for (Map.Entry<String, JsonNode> e : entries(root.path("variable"))) {
            JsonNode v = e.getValue();
            String typeText = v.path("type").isTextual() ? v.path("type").asText() : null;
            Hcl.Type type = typeText == null ? null : Hcl.type(Hcl.expression(typeText));
            JsonNode condition = v.path("validation").isArray() ? v.path("validation") : F.arrayNode().add(v.path("validation"));
            List<String> conditions = new ArrayList<>();
            condition.forEach(c -> {
                if (c.path("condition").isTextual()) {
                    conditions.add(c.path("condition").asText().replaceAll("^\\$\\{|\\}$", ""));
                }
            });
            variables.add(variable(e.getKey(), typeText, type, v.has("default"), v.get("default"), v.path("sensitive").asBoolean(false),
                    v.path("description").isTextual() ? v.path("description").asText() : null, conditions, path));
        }
        for (Map.Entry<String, JsonNode> e : entries(root.path("output"))) {
            JsonNode o = e.getValue();
            outputs.add(new Output(e.getKey(), o.path("description").isTextual() ? o.path("description").asText() : null,
                    o.path("sensitive").asBoolean(false), path));
        }
    }

    /** The named blocks of a JSON configuration: an object of names, or a list of such objects. */
    private static List<Map.Entry<String, JsonNode>> entries(JsonNode node) {
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        if (node.isObject()) {
            node.properties().forEach(out::add);
        } else if (node.isArray()) {
            node.forEach(n -> n.properties().forEach(out::add));
        }
        return out;
    }

    private static Variable variable(String name, String typeText, Hcl.Type type, boolean hasDefault, JsonNode defaultValue, boolean sensitive,
            String description, List<String> conditions, String file) {
        ObjectNode schema;
        if (type != null) {
            schema = type.schema().deepCopy();
        } else if (defaultValue != null && !defaultValue.isNull()) {
            schema = Schemas.infer(defaultValue);
        } else {
            schema = F.objectNode();
        }
        if (description != null && !description.isBlank()) {
            schema.put("description", description.trim());
        }
        if (defaultValue != null && !defaultValue.isNull()) {
            schema.set("default", defaultValue);
        }
        for (String condition : conditions) {
            if (condition.length() > MAX_CONDITION) {
                continue;
            }
            Matcher m = Pattern.compile(String.format(ENUM_CONDITION, Pattern.quote(name))).matcher(condition);
            if (m.find()) {
                JsonNode values = Hcl.literal(Hcl.expression(m.group(1)));
                if (values != null && values.isArray() && !values.isEmpty() && allScalars(values)) {
                    schema.set("enum", values);
                }
            }
        }
        return new Variable(name, typeText != null ? typeText : "any", !hasDefault, sensitive, description == null || description.isBlank() ? null : description.trim(),
                defaultValue, file, schema);
    }

    private static boolean allScalars(JsonNode values) {
        for (JsonNode v : values) {
            if (!v.isValueNode() || v.isNull()) {
                return false;
            }
        }
        return true;
    }

    /** The inputs of Plan and Apply: every variable but the secret ones, required ones first. */
    public static ObjectNode vars(Interface module) {
        ObjectNode schema = F.objectNode();
        schema.put("type", "object");
        ArrayNode required = F.arrayNode();
        ObjectNode properties = F.objectNode();
        for (boolean wantRequired : new boolean[] { true, false }) {
            for (Variable v : module.variables()) {
                if (v.secret() || v.required() != wantRequired || properties.has(v.name())) {
                    continue;
                }
                properties.set(v.name(), v.schema());
                if (v.required()) {
                    required.add(v.name());
                }
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        schema.set("properties", properties);
        return schema;
    }

    /**
     * The draft provider: {@code module} pins the source and version (and the commit read), Plan
     * previews Apply, both take the module's variables, and Apply returns the module's outputs.
     */
    public static ObjectNode provider(Draft d, Interface module) {
        ObjectNode root = F.objectNode();
        root.put("apiVersion", "iaf.platform/v1");
        root.put("kind", ProviderDefinition.KIND);
        ObjectNode md = root.putObject("metadata");
        md.put("name", d.name());
        md.put("title", d.title());
        md.put("owner", d.owner());
        md.put("version", d.version());
        if (d.description() != null) {
            md.put("description", d.description());
        }
        ObjectNode spec = root.putObject("spec");
        spec.put("type", ProviderType.TERRAFORM.key());
        ObjectNode m = spec.putObject("module");
        m.put("source", d.source());
        m.put("version", d.moduleVersion());
        if (d.commit() != null) {
            m.put("commit", d.commit());
        }
        ObjectNode auth = spec.putObject("auth");
        auth.put("type", "none");
        if (d.credential() != null) {
            auth.put("credential", d.credential());
        }
        ObjectNode vars = vars(module);
        ArrayNode actions = spec.putArray("actions");

        ObjectNode plan = actions.addObject();
        plan.put("id", "plan");
        plan.put("title", "Plan");
        plan.put("effect", "read");
        plan.put("retry", "safe");
        plan.set("vars", vars.deepCopy());
        plan.set("output", counts());
        plan.put("description", "terraform plan with these variables: what Apply would add, change and destroy");

        ObjectNode apply = actions.addObject();
        apply.put("id", "apply");
        apply.put("title", "Apply");
        apply.put("effect", "create");
        apply.put("retry", "unsafe");
        apply.put("plan", "plan");
        apply.set("vars", vars.deepCopy());
        ObjectNode output = counts();
        ObjectNode outputs = F.objectNode();
        outputs.put("type", "object");
        ObjectNode outputProps = outputs.putObject("properties");
        for (Output o : module.outputs()) {
            if (o.sensitive()) {
                continue;
            }
            ObjectNode p = outputProps.putObject(o.name());
            if (o.description() != null && !o.description().isBlank()) {
                p.put("description", o.description().trim());
            }
        }
        ObjectNode props = (ObjectNode) output.get("properties");
        ObjectNode reordered = F.objectNode();
        reordered.set("outputs", outputs);
        reordered.setAll(props);
        output.set("properties", reordered);
        apply.set("output", output);
        apply.put("description", "terraform apply with the same variables as the plan; returns the module's outputs");

        ObjectNode slo = spec.putObject("slo");
        slo.put("successRate", 95.0);
        slo.put("p95Ms", 600000);
        return root;
    }

    /** {@code { add, change, destroy }}: what a plan counts and an apply did. */
    private static ObjectNode counts() {
        ObjectNode s = F.objectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        for (String k : List.of("add", "change", "destroy")) {
            p.putObject(k).put("type", "integer");
        }
        return s;
    }
}

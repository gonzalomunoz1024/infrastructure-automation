package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Ids;

/**
 * One {@code kind: Workflow} file, parsed. The same record is what the designer edits, what the
 * catalog generates endpoints from, and what the interpreter walks (pinned by version).
 */
public record WorkflowDefinition(
        String apiVersion,
        Metadata metadata,
        Api api,
        Mcp mcp,
        Policy policy,
        List<Trigger> triggers,
        Rollout rollout,
        Flow workflow) {

    public static final String API_VERSION = "iaf.platform/v1";
    public static final String KIND = "Workflow";

    public record Metadata(String name, String title, String noun, String verb, String persona, String owner,
            String folder, List<String> tags, String version, String description) {
    }

    public record Api(String method, String path, JsonNode input, int responseStatus, boolean preview, BatchPolicy batch, Deprecation deprecated) {
        public Api(String method, String path, JsonNode input, int responseStatus, boolean preview, BatchPolicy batch) {
            this(method, path, input, responseStatus, preview, batch, null);
        }
    }

    /**
     * {@code api.deprecated}: the operation still answers, with {@code Deprecation}, {@code Sunset}
     * and (with a replacement) {@code Link: <...>; rel="successor-version"} headers. Dates are
     * {@code YYYY-MM-DD} or ISO date-times, as written; the checks judge them.
     */
    public record Deprecation(String since, String sunset, String replacement, String message) {
    }

    public record BatchPolicy(int max, RolloutPolicy rollout) {
        public static final BatchPolicy DEFAULT = new BatchPolicy(500, RolloutPolicy.DEFAULT);
    }

    public record RolloutPolicy(List<Integer> waves, int maxParallel, int stopIfFailedPercent) {
        public static final RolloutPolicy DEFAULT = new RolloutPolicy(List.of(5, 25, 100), 10, 5);
    }

    public record Mcp(boolean expose, String description) {
    }

    public record Policy(ApprovalPolicy approval, List<String> rbac, boolean changeRequired) {
    }

    public record ApprovalPolicy(boolean required, String group, String timeout) {
    }

    public record Trigger(String type, JsonNode spec) {
    }

    public record Rollout(String strategy, List<Integer> stages) {
        public static final Rollout CANARY = new Rollout("canary", List.of(10, 50, 100));
    }

    public record Flow(@JsonProperty("do") List<Step> steps, @JsonProperty("catch") Flow catchFlow, Map<String, String> outputs) {
    }

    public String name() {
        return metadata.name();
    }

    public String version() {
        return metadata.version() == null ? "0.1.0" : metadata.version();
    }

    public String title() {
        return metadata.title() == null ? metadata.name() : metadata.title();
    }

    public String noun() {
        return metadata.noun() == null ? "" : metadata.noun();
    }

    public String verb() {
        return metadata.verb() == null ? "" : metadata.verb();
    }

    /** The REST method: explicit, else inferred from the verb. */
    public String method() {
        if (api != null && api.method() != null) {
            return api.method().toUpperCase();
        }
        return switch (verb()) {
            case "read", "get", "list" -> "GET";
            case "update", "change" -> "PUT";
            case "delete", "destroy" -> "DELETE";
            default -> "POST";
        };
    }

    /** The REST path: explicit, else {@code /api/v1/<noun>s} for create and {@code /api/v1/<noun>s:<verb>} otherwise. */
    public String path() {
        if (api != null && api.path() != null) {
            return api.path();
        }
        String plural = Ids.slug(noun()) + "s";
        return switch (verb()) {
            case "create" -> "/api/v1/" + plural;
            case "read", "get" -> "/api/v1/" + plural + "/{id}";
            default -> "/api/v1/" + plural + ":" + Ids.slug(verb());
        };
    }

    /**
     * The batch route. A path that ends in a parameter, like {@code /api/v1/vms/{vmId}:restart},
     * names one resource, so its batch sits on the collection ({@code /api/v1/vms:restart:batch})
     * and every row brings its own {@code vmId}; any other path just adds {@code :batch}. Parameters
     * earlier in the path still apply to every row.
     */
    public String batchPath() {
        String path = path();
        Matcher m = TRAILING_PARAM.matcher(path);
        if (!m.matches()) {
            return path + ":batch";
        }
        String action = m.group(2) != null ? m.group(2) : ":" + (VERB.matcher(verb()).matches() ? verb() : method().toLowerCase());
        return m.group(1) + action + ":batch";
    }

    private static final Pattern TRAILING_PARAM = Pattern.compile("^(.*)/\\{[^/{}]+}(:[^/{}:]+)?$");
    private static final Pattern VERB = Pattern.compile("^[a-z][a-z0-9-]*$");

    public JsonNode inputSchema() {
        return api == null ? null : api.input();
    }

    public boolean mcpExposed() {
        return mcp != null && mcp.expose();
    }

    /** The MCP tool name: {@code vm_restart}. */
    public String mcpTool() {
        return (Ids.slug(noun()) + "_" + Ids.slug(verb())).replace('-', '_');
    }

    public BatchPolicy batchPolicy() {
        return api == null || api.batch() == null ? BatchPolicy.DEFAULT : api.batch();
    }

    public Rollout rolloutOrDefault() {
        return rollout == null ? Rollout.CANARY : rollout;
    }

    public boolean previewEnabled() {
        return api != null && api.preview();
    }

    /** {@code api.deprecated}, or null when the operation is not deprecated. */
    public Deprecation deprecation() {
        return api == null ? null : api.deprecated();
    }

    public List<Step> steps() {
        return workflow == null || workflow.steps() == null ? List.of() : workflow.steps();
    }

    /** Every step at every depth, in document order. */
    public List<Step> allSteps() {
        List<Step> out = new ArrayList<>();
        steps().forEach(s -> out.addAll(s.flatten()));
        if (workflow != null && workflow.catchFlow() != null && workflow.catchFlow().steps() != null) {
            workflow.catchFlow().steps().forEach(s -> out.addAll(s.flatten()));
        }
        return out;
    }

    public Optional<Step> step(String idOrName) {
        return allSteps().stream().filter(s -> s.id().equals(idOrName) || idOrName.equals(s.name())).findFirst();
    }

    public WorkflowDefinition withMetadata(Metadata m) {
        return new WorkflowDefinition(apiVersion, m, api, mcp, policy, triggers, rollout, workflow);
    }

    public WorkflowDefinition withVersion(String version) {
        Metadata m = metadata;
        return withMetadata(new Metadata(m.name(), m.title(), m.noun(), m.verb(), m.persona(), m.owner(), m.folder(), m.tags(), version, m.description()));
    }

    public WorkflowDefinition withFlow(Flow flow) {
        return new WorkflowDefinition(apiVersion, metadata, api, mcp, policy, triggers, rollout, flow);
    }
}

package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public record Api(String method, String path, JsonNode input, int responseStatus, boolean preview, BatchPolicy batch) {
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

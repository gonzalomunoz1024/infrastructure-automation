package com.iaf.platform.providers.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Effect;

/**
 * One {@code kind: Provider} file, parsed. A provider says where a system's API is, how to sign
 * in and which operations to offer; the real API stays in its own system.
 */
public record ProviderDefinition(
        String apiVersion,
        Metadata metadata,
        Spec spec) {

    public static final String KIND = "Provider";

    public record Metadata(String name, String title, String owner, String version, String description) {
    }

    public record Spec(
            ProviderType type,
            SpecSource specSource,
            Map<String, String> endpoints,
            Auth auth,
            List<Action> actions,
            Map<String, JsonNode> overrides,
            List<ErrorClass> errors,
            Slo slo,
            Alerts alerts,
            JsonNode source,       // ansible: { git | upload | library, commit }
            JsonNode module,       // terraform: { source, version }
            JsonNode tile,         // idp: { id }
            Worker worker,         // sdk: the team's own worker
            String executionEnvironment) {
    }

    /**
     * The worker an sdk provider's team runs: the Temporal task queue it listens on (blank =
     * {@code provider-<name>}) and how long it may go without a heartbeat before the platform
     * counts it lost (a duration such as {@code 30s}; blank = no heartbeat timeout).
     */
    public record Worker(String taskQueue, String heartbeatTimeout) {
    }

    /**
     * Where the OpenAPI document is: a {@code url}, pasted {@code content}, or a file in a Git
     * repository ({@code git}) pinned to the {@code commit} it was read from. {@code sha256} pins
     * the document itself.
     */
    public record SpecSource(String url, String sha256, String content, GitRef git, String commit) {

        public SpecSource(String url, String sha256, String content) {
            this(url, sha256, content, null, null);
        }

        public boolean fromGit() {
            return git != null;
        }

        public boolean pasted() {
            return git == null && (url == null || url.isBlank()) && content != null && !content.isBlank();
        }
    }

    /** A file in a Git repository: {@code ref} is a branch, tag or commit; {@code credential} a {@code vault:} reference to a read token. */
    public record GitRef(String repo, String ref, String path, String credential) {
    }

    public record Auth(String type, String credential, String header) {
    }

    public record Action(
            String id,
            String title,
            String operation,          // operationId (openapi) or null
            String method,             // http: explicit method/path
            String path,
            Effect effect,
            boolean retrySafe,
            boolean approvalRequired,
            String plan,               // preview via another action id
            Verify verify,
            JsonNode input,            // JSON Schema
            JsonNode output,           // JSON Schema
            JsonNode sample,           // recorded response (the dry-run mock)
            JsonNode example,          // http without a spec: one example response
            String playbook,           // ansible
            JsonNode vars,             // ansible/terraform: variables that become inputs
            String description,
            String effectDetail,       // one line the preview shows when there is no plan action
            JsonNode recorded) {       // where the sample came from: { environment, at }

        /** An action without a declared effect detail or a recorded sample origin. */
        public Action(String id, String title, String operation, String method, String path, Effect effect, boolean retrySafe,
                boolean approvalRequired, String plan, Verify verify, JsonNode input, JsonNode output, JsonNode sample, JsonNode example,
                String playbook, JsonNode vars, String description) {
            this(id, title, operation, method, path, effect, retrySafe, approvalRequired, plan, verify, input, output, sample, example,
                    playbook, vars, description, null, null);
        }

        public boolean destructive() {
            return effect != null && effect.destructive();
        }
    }

    public record Verify(String action, String expect) {
    }

    public record ErrorClass(JsonNode match, String errorClass, boolean retry) {
    }

    public record Slo(double successRate, int p95Ms) {
        public static final Slo DEFAULT = new Slo(99.0, 1000);
    }

    public record Alerts(String channel) {
    }

    public String name() {
        return metadata.name();
    }

    public String title() {
        return metadata.title() == null ? metadata.name() : metadata.title();
    }

    public String owner() {
        return metadata.owner();
    }

    public ProviderType type() {
        return spec.type();
    }

    /** The task queue an sdk provider's worker listens on: its own, or {@code provider-<name>}. */
    public String taskQueue() {
        Worker w = spec.worker();
        return w == null || w.taskQueue() == null || w.taskQueue().isBlank() ? defaultTaskQueue(name()) : w.taskQueue().trim();
    }

    public static String defaultTaskQueue(String name) {
        return "provider-" + name;
    }

    public List<Action> actions() {
        return spec.actions() == null ? List.of() : spec.actions();
    }

    public Optional<Action> action(String id) {
        return actions().stream().filter(a -> a.id().equals(id)).findFirst();
    }

    public Slo slo() {
        return spec.slo() == null ? Slo.DEFAULT : spec.slo();
    }

    public List<String> environments() {
        return spec.endpoints() == null ? List.of() : List.copyOf(spec.endpoints().keySet());
    }

    /** The override block for an action, or null. */
    public JsonNode override(String action) {
        return spec.overrides() == null ? null : spec.overrides().get(action);
    }

    /** The output schema calls are judged against: the declared one with the action's override merged in. */
    public JsonNode effectiveOutput(Action action) {
        JsonNode override = override(action.id());
        if (override != null && override.has("response")) {
            return com.iaf.platform.common.schema.Schemas.merge(action.output(), override.get("response"));
        }
        return action.output();
    }
}

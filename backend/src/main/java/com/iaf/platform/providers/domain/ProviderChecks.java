package com.iaf.platform.providers.domain;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.domain.Problem.Fix;
import com.iaf.platform.common.domain.Suggest;
import com.iaf.platform.common.expressions.ExpressionEngine;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/**
 * The checks a provider file passes before it is published: the same rules run on the validate
 * endpoint, in the wizard's review step and as the change request's CI checks. Pure: no lookups.
 *
 * <p>Every problem belongs to one of five checks ({@code schema}, {@code spec-pinned},
 * {@code credentials}, {@code actions}, {@code slo}); a check fails when one of its problems is
 * an error, is skipped when it does not apply to the provider's type, and passes otherwise.
 * Codes are stable identifiers the editor keys on; {@code step} carries the action id.</p>
 */
public final class ProviderChecks {

    public static final String SCHEMA = "schema";
    public static final String SPEC_PINNED = "spec-pinned";
    public static final String CREDENTIALS = "credentials";
    public static final String ACTIONS = "actions";
    public static final String SLO = "slo";
    public static final List<String> CHECK_IDS = List.of(SCHEMA, SPEC_PINNED, CREDENTIALS, ACTIONS, SLO);

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern ACTION_ID = Pattern.compile("^[a-z][A-Za-z0-9]*\\.[a-z][A-Za-z0-9]*$");
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-fA-F]{7,40}$");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(password|passwd|secret|token|apikey|api_key|api-key|credential|privateKey)");
    private static final Set<String> AUTH_TYPES = Set.of("bearer", "basic", "header", "none");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> VERIFY_EXPECTS = Set.of("notFound", "ok", "exists");
    /** Types whose action ids are fixed by the tool (plan/apply/destroy) or by the portal (tile ids). */
    private static final Set<ProviderType> FREE_ACTION_IDS = Set.of(ProviderType.TERRAFORM, ProviderType.IDP);
    /** Types that run through a tool or a worker and have no HTTP endpoints. */
    private static final Set<ProviderType> NO_ENDPOINTS = Set.of(ProviderType.SDK, ProviderType.ANSIBLE, ProviderType.TERRAFORM, ProviderType.IDP);

    private ProviderChecks() {
    }

    /** The checks and the problems behind them; {@code problems} is what the editor lists. */
    public record Result(List<Check> checks, List<Problem> problems) {
        public boolean hasErrors() {
            return problems.stream().anyMatch(Problem::isError);
        }

        public List<Problem> errors() {
            return problems.stream().filter(Problem::isError).toList();
        }
    }

    /** Check YAML text: a syntax error is one problem under the schema check. */
    public static Result check(String yaml) {
        JsonNode root;
        try {
            root = WorkflowYaml.readTree(yaml);
        } catch (IllegalArgumentException e) {
            Checker c = new Checker(null);
            c.error(SCHEMA, "yaml.invalid", null, null, e.getMessage());
            c.skipAllBut(SCHEMA);
            return c.result();
        }
        return check(root);
    }

    public static Result check(JsonNode root) {
        Checker c = new Checker(root);
        c.run();
        return c.result();
    }

    public static boolean hasErrors(List<Problem> problems) {
        return problems.stream().anyMatch(Problem::isError);
    }

    private static final class Checker {
        final JsonNode root;
        final List<Problem> problems = new ArrayList<>();
        final Map<String, List<Problem>> byCheck = new LinkedHashMap<>();
        final Set<String> skipped = new HashSet<>();
        final Map<String, String> details = new LinkedHashMap<>();
        ProviderDefinition def;
        String name = "provider";

        Checker(JsonNode root) {
            this.root = root;
            CHECK_IDS.forEach(id -> byCheck.put(id, new ArrayList<>()));
        }

        void run() {
            if (root == null || !root.isObject() || root.isEmpty()) {
                error(SCHEMA, "yaml.invalid", null, null, "A provider file is a YAML mapping with kind: Provider");
                skipAllBut(SCHEMA);
                return;
            }
            String kind = root.path("kind").asText(null);
            if (!ProviderDefinition.KIND.equals(kind)) {
                error(SCHEMA, "kind.invalid", null, "kind", "kind must be Provider" + (kind == null ? "" : ", was " + kind));
            }
            metadata();
            JsonNode spec = root.path("spec");
            if (!spec.isObject()) {
                error(SCHEMA, "spec.missing", null, "spec", "spec is required: the type, endpoints, auth and actions of the provider");
                skipAllBut(SCHEMA);
                return;
            }
            String typeKey = spec.path("type").asText("openapi");
            ProviderType type;
            try {
                type = ProviderType.of(typeKey);
            } catch (IllegalArgumentException e) {
                List<String> keys = Arrays.stream(ProviderType.values()).map(ProviderType::key).toList();
                error(SCHEMA, "type.unknown", null, "spec.type", "Unknown provider type '" + typeKey + "'; one of " + keys, suggest(typeKey, keys));
                skipAllBut(SCHEMA);
                return;
            }
            try {
                def = ProviderYaml.fromJson(root);
            } catch (IllegalArgumentException e) {
                error(SCHEMA, "yaml.invalid", null, null, e.getMessage());
                skipAllBut(SCHEMA);
                return;
            }
            name = def.name();
            endpoints(type);
            typeSpecific(type, spec);
            specPinned(type, spec);
            credentials(spec);
            actions(type, spec);
            slo(spec);
        }

        // ------------------------------------------------------------ schema

        void metadata() {
            JsonNode md = root.path("metadata");
            String n = md.path("name").asText(null);
            if (n == null || !NAME.matcher(n).matches()) {
                error(SCHEMA, "name.format", null, "metadata.name", "The name is the file name and the id workflows use: lowercase letters, digits and dashes, like vsphere");
            }
            if (md.path("title").asText("").isBlank()) {
                warning(SCHEMA, "metadata.title", null, "metadata.title", "Give the provider a title; the registry and the designer show it");
            }
            if (md.path("owner").asText("").isBlank()) {
                error(SCHEMA, "metadata.owner", null, "metadata.owner", "owner is the team that may edit and publish this provider");
            }
            String version = md.path("version").asText(null);
            if (version == null || !SEMVER.matcher(version).matches()) {
                error(SCHEMA, "version.semver", null, "metadata.version", "version must be semantic, like 1.0.0; publishing opens a change request for that version");
            }
        }

        void endpoints(ProviderType type) {
            Map<String, String> endpoints = def.spec().endpoints() == null ? Map.of() : def.spec().endpoints();
            if (endpoints.isEmpty()) {
                if (!NO_ENDPOINTS.contains(type)) {
                    error(SCHEMA, "endpoints.missing", null, "spec.endpoints", "List at least one environment endpoint, like dev: https://api-dev.example.com");
                }
                return;
            }
            endpoints.forEach((env, url) -> {
                String field = "spec.endpoints." + env;
                if (url == null || url.isBlank()) {
                    error(SCHEMA, "endpoints.url", null, field, "The " + env + " endpoint is empty");
                    return;
                }
                URI uri;
                try {
                    uri = URI.create(url.trim());
                } catch (IllegalArgumentException e) {
                    error(SCHEMA, "endpoints.url", null, field, "The " + env + " endpoint is not a URL: " + url);
                    return;
                }
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    error(SCHEMA, "endpoints.https", null, field, "Endpoints are https URLs; " + env + " is " + url,
                            url.startsWith("http://") ? Fix.replace("Use https", "https://" + url.substring("http://".length())) : null);
                }
            });
        }

        void typeSpecific(ProviderType type, JsonNode spec) {
            switch (type) {
                case ANSIBLE -> {
                    JsonNode source = spec.get("source");
                    if (source == null || !source.isObject()) {
                        error(SCHEMA, "ansible.source", null, "spec.source", "An Ansible provider needs source: { git: { repo, ref }, commit } or upload or library");
                    } else if (source.has("git")) {
                        String commit = source.path("commit").asText(null);
                        if (commit == null || commit.isBlank()) {
                            warning(SCHEMA, "ansible.unpinned", null, "spec.source.commit", "Pin the repository to a commit so every run uses the same playbooks");
                        } else if (!COMMIT.matcher(commit).matches()) {
                            warning(SCHEMA, "ansible.unpinned", null, "spec.source.commit", "commit should be a Git commit hash, was " + commit);
                        }
                    }
                }
                case TERRAFORM -> {
                    JsonNode module = spec.get("module");
                    if (module == null || !module.isObject() || module.path("source").asText("").isBlank()) {
                        error(SCHEMA, "terraform.module", null, "spec.module", "A Terraform provider needs module: { source, version }");
                    } else if (module.path("version").asText("").isBlank()) {
                        error(SCHEMA, "terraform.version", null, "spec.module.version", "Pin module.version so every run uses the same code");
                    }
                }
                case IDP -> {
                    if (spec.path("tile").path("id").asText("").isBlank()) {
                        error(SCHEMA, "idp.tile", null, "spec.tile", "An IDP provider names its tile: tile: { id }");
                    }
                }
                case SDK -> {
                    if (spec.path("taskQueue").asText("").isBlank()) {
                        error(SCHEMA, "sdk.taskQueue", null, "spec.taskQueue", "An SDK provider names the task queue its worker listens on");
                    }
                }
                default -> {
                }
            }
        }

        // ------------------------------------------------------------ spec-pinned

        void specPinned(ProviderType type, JsonNode spec) {
            if (type != ProviderType.OPENAPI) {
                skipped.add(SPEC_PINNED);
                details.put(SPEC_PINNED, "Not an OpenAPI provider");
                return;
            }
            ProviderDefinition.SpecSource source = def.spec().specSource();
            boolean hasDoc = source != null && ((source.url() != null && !source.url().isBlank()) || (source.content() != null && !source.content().isBlank()));
            if (!hasDoc) {
                error(SPEC_PINNED, "spec.missing", null, "spec.spec", "An OpenAPI provider needs spec: { url, sha256 }; the wizard fills it from Import spec");
                return;
            }
            String sha = source.sha256();
            if (sha == null || sha.isBlank()) {
                error(SPEC_PINNED, "spec.unpinned", null, "spec.spec.sha256", "Pin the spec with its sha256 so operations cannot drift under a published provider");
            } else if (!SHA256.matcher(sha).matches()) {
                error(SPEC_PINNED, "spec.sha256", null, "spec.spec.sha256", "sha256 is 64 hex characters, was " + sha);
            } else {
                details.put(SPEC_PINNED, "Pinned to " + sha.substring(0, 12) + "…");
            }
        }

        // ------------------------------------------------------------ credentials

        void credentials(JsonNode spec) {
            ProviderDefinition.Auth auth = def.spec().auth();
            String type = auth == null || auth.type() == null ? "none" : auth.type();
            if (!AUTH_TYPES.contains(type)) {
                error(CREDENTIALS, "auth.type", null, "spec.auth.type", "auth.type is bearer, basic, header or none; was " + type, suggest(type, AUTH_TYPES));
            }
            if ("header".equals(type) && (auth.header() == null || auth.header().isBlank())) {
                error(CREDENTIALS, "auth.header", null, "spec.auth.header", "Header auth names the header that carries the key, like X-Api-Key");
            }
            String credential = auth == null ? null : auth.credential();
            String suggested = "vault:" + name + "/" + switch (type) {
                case "basic" -> "basic";
                case "header" -> "api-key";
                default -> "token";
            };
            if (credential == null || credential.isBlank()) {
                if (!"none".equals(type)) {
                    error(CREDENTIALS, "credential.missing", null, "spec.auth.credential", "Reference the credential in Vault as vault:<path>; the platform resolves it at call time",
                            Fix.replace("Use a Vault reference", suggested));
                }
            } else if (!credential.startsWith("vault:")) {
                error(CREDENTIALS, "credential.plain", null, "spec.auth.credential", "Secrets never go in a provider file; reference Vault with vault:<path>",
                        Fix.replace("Use a Vault reference", suggested));
            } else if (credential.length() <= "vault:".length()) {
                error(CREDENTIALS, "credential.missing", null, "spec.auth.credential", "The Vault reference needs a path, like " + suggested,
                        Fix.replace("Use a Vault reference", suggested));
            }
            spec.fields().forEachRemaining(e -> {
                if ("auth".equals(e.getKey())) {
                    return;
                }
                if ("actions".equals(e.getKey()) && e.getValue().isArray()) {
                    int i = 0;
                    for (JsonNode a : e.getValue()) {
                        secrets(a, a.path("id").asText(null), "spec.actions[" + i++ + "]");
                    }
                    return;
                }
                secrets(e.getValue(), null, "spec." + e.getKey());
            });
            if ("none".equals(type) && byCheck.get(CREDENTIALS).isEmpty() && (credential == null || credential.isBlank())) {
                skipped.add(CREDENTIALS);
                details.put(CREDENTIALS, "No credential");
            } else if (byCheck.get(CREDENTIALS).isEmpty()) {
                details.put(CREDENTIALS, credential);
            }
        }

        void secrets(JsonNode node, String step, String path) {
            if (node == null) {
                return;
            }
            if (node.isObject()) {
                node.fields().forEachRemaining(e -> {
                    String p = path + "." + e.getKey();
                    JsonNode v = e.getValue();
                    if (v.isTextual() && SECRET_KEY.matcher(e.getKey()).find() && !v.asText().startsWith("vault:")
                            && !ExpressionEngine.isTemplate(v.asText()) && !v.asText().isBlank()) {
                        error(CREDENTIALS, "credential.plain", step, p, "Secrets never go in a provider file; reference Vault with vault:<path>",
                                Fix.replace("Use a Vault reference", "vault:" + name + "/" + e.getKey()));
                    } else {
                        secrets(v, step, p);
                    }
                });
            } else if (node.isArray()) {
                int i = 0;
                for (JsonNode v : node) {
                    secrets(v, step, path + "[" + i++ + "]");
                }
            }
        }

        // ------------------------------------------------------------ actions

        void actions(ProviderType type, JsonNode spec) {
            List<Action> actions = def.actions();
            if (actions.isEmpty()) {
                error(ACTIONS, "actions.empty", null, "spec.actions", "Choose at least one action; workflows can only call what the provider offers");
                return;
            }
            Set<String> ids = new HashSet<>();
            List<String> allIds = actions.stream().map(Action::id).toList();
            int i = 0;
            for (Action a : actions) {
                String field = "spec.actions[" + i++ + "]";
                if (!ids.add(a.id())) {
                    error(ACTIONS, "action.duplicateId", a.id(), field + ".id", "Action id '" + a.id() + "' is used twice");
                }
                if (!FREE_ACTION_IDS.contains(type) && !ACTION_ID.matcher(a.id()).matches()) {
                    warning(ACTIONS, "action.idShape", a.id(), field + ".id", "Action ids read as <noun>.<verb>, like vm.get or jobs.list");
                }
                switch (type) {
                    case OPENAPI -> {
                        if ((a.operation() == null || a.operation().isBlank()) && (a.method() == null || a.path() == null)) {
                            error(ACTIONS, "action.operation", a.id(), field + ".operation", "Name the operationId of the spec this action calls");
                        }
                    }
                    case HTTP -> {
                        if (a.method() == null || a.path() == null || a.path().isBlank()) {
                            error(ACTIONS, "action.operation", a.id(), field + ".operation", "An HTTP action needs operation: { method, path }");
                        } else if (!METHODS.contains(a.method().toUpperCase())) {
                            error(ACTIONS, "action.method", a.id(), field + ".operation.method", "method must be one of GET, POST, PUT, PATCH, DELETE");
                        }
                    }
                    case ANSIBLE -> {
                        if (a.playbook() == null || a.playbook().isBlank()) {
                            error(ACTIONS, "action.playbook", a.id(), field + ".playbook", "An Ansible action names the playbook file in the repository");
                        }
                    }
                    default -> {
                    }
                }
                if (a.effect() == null || a.effect() == Effect.UNKNOWN) {
                    warning(ACTIONS, "action.effect", a.id(), field + ".effect", "Say what the action does (read, create, change or delete); previews and approvals depend on it");
                }
                if (a.destructive() && !a.approvalRequired()) {
                    error(ACTIONS, "action.approvalRequired", a.id(), field + ".approval", "A delete action needs an approval before it runs",
                            Fix.replace("Require an approval", "required"));
                }
                if (a.effect() == Effect.READ && !a.retrySafe()) {
                    warning(ACTIONS, "action.retryUnsafeRead", a.id(), field + ".retry", "A read is safe to retry; mark it retry: safe so transient errors recover");
                }
                if (a.plan() != null && !allIds.contains(a.plan())) {
                    error(ACTIONS, "action.unknownPlan", a.id(), field + ".plan", "plan refers to an unknown action '" + a.plan() + "'", suggest(a.plan(), allIds));
                }
                if (a.verify() != null) {
                    if (a.verify().action() == null || !allIds.contains(a.verify().action())) {
                        error(ACTIONS, "action.unknownVerify", a.id(), field + ".verify.action", "verify refers to an unknown action '" + a.verify().action() + "'",
                                suggest(a.verify().action(), allIds));
                    }
                    if (a.verify().expect() == null || !VERIFY_EXPECTS.contains(a.verify().expect())) {
                        warning(ACTIONS, "action.verifyExpect", a.id(), field + ".verify.expect", "verify.expect is notFound, ok or exists");
                    }
                }
                schema(a.input(), a.id(), field + ".input");
                schema(a.output(), a.id(), field + ".output");
                schema(a.vars(), a.id(), field + ".vars");
            }
            if (def.spec().overrides() != null) {
                for (String target : def.spec().overrides().keySet()) {
                    if (!allIds.contains(target)) {
                        error(ACTIONS, "override.unknownAction", target, "spec.overrides." + target, "overrides refer to an unknown action '" + target + "'", suggest(target, allIds));
                    }
                }
            }
            if (byCheck.get(ACTIONS).stream().noneMatch(Problem::isError)) {
                details.put(ACTIONS, actions.size() + (actions.size() == 1 ? " action" : " actions"));
            }
        }

        void schema(JsonNode schema, String action, String field) {
            if (schema == null || schema.isNull()) {
                return;
            }
            if (!schema.isObject() || (schema.has("type") && !"object".equals(schema.path("type").asText()))) {
                error(ACTIONS, "action.schema", action, field, field.substring(field.lastIndexOf('.') + 1) + " is a JSON Schema of type object");
            }
        }

        // ------------------------------------------------------------ slo

        void slo(JsonNode spec) {
            JsonNode sl = spec.get("slo");
            if (sl == null || sl.isNull()) {
                details.put(SLO, "Defaults: " + ProviderHealthRule.pct(ProviderDefinition.Slo.DEFAULT.successRate()) + " success, p95 " + ProviderDefinition.Slo.DEFAULT.p95Ms() + " ms");
            } else {
                double rate = sl.path("successRate").asDouble(ProviderDefinition.Slo.DEFAULT.successRate());
                if (!sl.path("successRate").isNumber() && sl.has("successRate") || rate < 0 || rate > 100) {
                    error(SLO, "slo.successRate", null, "spec.slo.successRate", "successRate is a percentage between 0 and 100");
                }
                int p95 = sl.path("p95Ms").asInt(ProviderDefinition.Slo.DEFAULT.p95Ms());
                if (!sl.path("p95Ms").isNumber() && sl.has("p95Ms") || p95 <= 0) {
                    error(SLO, "slo.p95Ms", null, "spec.slo.p95Ms", "p95Ms is a positive number of milliseconds");
                }
                if (byCheck.get(SLO).isEmpty()) {
                    details.put(SLO, ProviderHealthRule.pct(rate) + " success, p95 " + p95 + " ms");
                }
            }
            if (spec.path("alerts").path("channel").asText("").isBlank()) {
                warning(SLO, "alerts.channel", null, "spec.alerts.channel", "Set alerts.channel so health alerts reach the owning team");
            }
        }

        // ------------------------------------------------------------ result

        Result result() {
            List<Check> checks = new ArrayList<>();
            for (String id : CHECK_IDS) {
                List<Problem> ps = byCheck.get(id);
                String status;
                if (ps.stream().anyMatch(Problem::isError)) {
                    status = "failed";
                } else if (skipped.contains(id)) {
                    status = "skipped";
                } else {
                    status = "passed";
                }
                checks.add(new Check(id, label(id), status, detail(id, ps)));
            }
            return new Result(checks, List.copyOf(problems));
        }

        String detail(String id, List<Problem> ps) {
            long errors = ps.stream().filter(Problem::isError).count();
            long warnings = ps.size() - errors;
            if (errors > 0) {
                return errors + (errors == 1 ? " error" : " errors") + (warnings > 0 ? ", " + warnings + (warnings == 1 ? " warning" : " warnings") : "")
                        + ": " + ps.stream().filter(Problem::isError).findFirst().map(Problem::message).orElse("");
            }
            String d = details.get(id);
            if (warnings > 0) {
                return (d == null ? "" : d + "; ") + warnings + (warnings == 1 ? " warning" : " warnings");
            }
            return d;
        }

        static String label(String id) {
            return switch (id) {
                case SCHEMA -> "Schema";
                case SPEC_PINNED -> "Spec pinned";
                case CREDENTIALS -> "Credentials";
                case ACTIONS -> "Actions";
                case SLO -> "SLO";
                default -> id;
            };
        }

        void skipAllBut(String check) {
            for (String id : CHECK_IDS) {
                if (!id.equals(check)) {
                    skipped.add(id);
                }
            }
        }

        Optional<Fix> suggest(String wrong, java.util.Collection<String> candidates) {
            return Suggest.closest(wrong, candidates).map(s -> Fix.replace("Did you mean " + s + "?", s));
        }

        void error(String check, String code, String step, String field, String message) {
            add(check, Problem.error(code, step, field, message));
        }

        void error(String check, String code, String step, String field, String message, Fix fix) {
            add(check, Problem.error(code, step, field, message, fix));
        }

        void error(String check, String code, String step, String field, String message, Optional<Fix> fix) {
            add(check, Problem.error(code, step, field, message, fix.orElse(null)));
        }

        void warning(String check, String code, String step, String field, String message) {
            add(check, Problem.warning(code, step, field, message));
        }

        void add(String check, Problem p) {
            problems.add(p);
            byCheck.get(check).add(p);
        }
    }
}

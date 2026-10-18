package com.iaf.platform.assistant.domain;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.WorkflowDraft.Missing;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderType;

/** Step bodies for provider actions, by the provider's type: the one place that knows how each type is called. */
public final class Steps {

    private Steps() {
    }

    /** The parameter schema of an action: {@code input} for API actions, {@code vars} for playbooks and modules. */
    public static JsonNode schemaOf(Action a) {
        if (a.input() != null && a.input().isObject()) {
            return a.input();
        }
        return a.vars() != null && a.vars().isObject() ? a.vars() : null;
    }

    public static boolean hasOutput(Action a, String field) {
        if (a == null) {
            return false;
        }
        if (a.output() != null && a.output().path("properties").has(field)) {
            return true;
        }
        if (a.sample() != null && a.sample().has(field)) {
            return true;
        }
        return a.example() != null && a.example().has(field);
    }

    /**
     * The body of a step calling an action. {@code input} holds the mapped parameters; {@code limit}
     * is the host expression for playbooks. A credential the registry cannot supply is reported in
     * {@code missing}.
     */
    public static ObjectNode callBody(ProviderDefinition p, Action a, ObjectNode input, String limit, List<Missing> missing) {
        ObjectNode body = WorkflowDoc.object();
        body.put("name", a.title() == null || a.title().isBlank() ? a.id() : a.title());
        ProviderType type = p.type() == null ? ProviderType.OPENAPI : p.type();
        switch (type) {
            case ANSIBLE -> {
                body.put("call", "ansible");
                ObjectNode with = body.putObject("with");
                JsonNode source = p.spec().source();
                if (source != null && source.has("git")) {
                    ObjectNode pb = with.putObject("playbook");
                    ObjectNode git = pb.putObject("git");
                    git.put("repo", source.path("git").path("repo").asText());
                    git.put("ref", source.path("git").path("ref").asText());
                    git.put("path", a.playbook() == null ? a.id() : a.playbook());
                    if (source.hasNonNull("commit")) {
                        pb.put("commit", source.get("commit").asText());
                    }
                } else if (source != null && source.has("upload")) {
                    with.putObject("playbook").set("upload", source.get("upload").deepCopy());
                } else {
                    with.put("playbook", p.name() + "/" + a.id());
                }
                with.put("inventory", "dynamic");
                if (limit != null) {
                    with.put("limit", limit);
                }
                with.set("extraVars", input);
                if (p.spec().auth() != null && p.spec().auth().credential() != null) {
                    with.put("credential", p.spec().auth().credential());
                } else if (missing != null) {
                    missing.add(new Missing("credential", "ssh credential for " + p.name(), "the playbook needs a vault:<name> credential to reach the hosts"));
                }
                if (p.spec().executionEnvironment() != null) {
                    with.put("executionEnvironment", p.spec().executionEnvironment());
                }
                body.putObject("retry").put("maxAttempts", 1);
            }
            case TERRAFORM -> {
                body.put("call", "terraform");
                ObjectNode with = body.putObject("with");
                if (p.spec().module() != null) {
                    with.set("module", p.spec().module().deepCopy());
                }
                String action = Set.of("plan", "apply", "destroy").contains(a.id()) ? a.id()
                        : a.effect() == Effect.DELETE ? "destroy" : a.effect() == Effect.READ ? "plan" : "apply";
                with.put("action", action);
                with.set("vars", input);
                if (p.spec().auth() != null && p.spec().auth().credential() != null) {
                    with.put("credential", p.spec().auth().credential());
                }
            }
            case IDP -> {
                body.put("call", "idp");
                ObjectNode with = body.putObject("with");
                with.put("tile", a.id());
                with.set("input", input);
            }
            default -> {
                // openapi, http and sdk: an sdk provider's actions run on its own worker, but a workflow calls them like any provider's
                body.put("call", "provider");
                ObjectNode with = body.putObject("with");
                with.put("provider", p.name());
                with.put("action", a.id());
                with.set("input", input);
            }
        }
        return body;
    }

    /** Whether a step body is a call whose effect changes the world (anything but a read). */
    public static boolean isWrite(ObjectNode body, Registry registry) {
        String kind = WorkflowDoc.kind(body);
        if (kind == null) {
            return false;
        }
        switch (kind) {
            case "provider" -> {
                ObjectNode with = WorkflowDoc.params(body);
                if (with == null) {
                    return false;
                }
                return registry.provider(with.path("provider").asText()).flatMap(p -> p.action(with.path("action").asText()))
                        .map(a -> a.effect() != Effect.READ).orElse(true);
            }
            case "ansible", "terraform", "idp", "activity", "run", "register" -> {
                return true;
            }
            case "http" -> {
                ObjectNode with = WorkflowDoc.params(body);
                return with == null || !"GET".equalsIgnoreCase(with.path("method").asText("GET"));
            }
            default -> {
                return false;
            }
        }
    }

    /** Whether a step body is destructive: a delete action, a terraform destroy, an HTTP DELETE. */
    public static boolean isDestructive(ObjectNode body, Registry registry) {
        String kind = WorkflowDoc.kind(body);
        ObjectNode with = WorkflowDoc.params(body);
        if (kind == null || with == null) {
            return false;
        }
        return switch (kind) {
            case "provider" -> registry.provider(with.path("provider").asText()).flatMap(p -> p.action(with.path("action").asText()))
                    .map(Action::destructive).orElse(false);
            case "terraform" -> "destroy".equals(with.path("action").asText());
            case "http" -> "DELETE".equalsIgnoreCase(with.path("method").asText());
            default -> false;
        };
    }
}

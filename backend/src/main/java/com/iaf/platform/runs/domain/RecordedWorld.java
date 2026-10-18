package com.iaf.platform.runs.domain;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.expressions.ExpressionContext;
import com.iaf.platform.common.expressions.ExpressionEngine;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.StepType;

/**
 * The recorded world every demo call is answered from: each action's {@code sample}, resolved
 * with {@code $input} = the call input and {@code $job} = {@code { id, seq }}. The world
 * remembers what changed so a flow reads back what it did: a {@code delete} makes later reads of
 * the same id answer {@code not_found} (which is how {@code verify: { expect: notFound }} comes
 * true), and a {@code change} overlays the fields it sent onto later reads of the id (an upgrade
 * to 8.0 is read back as 8.0). Shared by the demo provider adapter, dry runs, the seed and tests.
 */
public final class RecordedWorld {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final Set<String> deleted = ConcurrentHashMap.newKeySet();
    private final Map<String, ObjectNode> changed = new ConcurrentHashMap<>();

    /** An automation answer: the output and, when a provider file described the playbook/module/tile, which one. */
    public record Answer(JsonNode output, ProviderDefinition provider, Action action) {
    }

    /** An action's sample resolved for one call; {@code { ok: true }} when the action has none. */
    public static JsonNode resolveSample(Action action, JsonNode input, String jobId, int seq) {
        if (action == null || action.sample() == null || action.sample().isNull()) {
            ObjectNode ok = F.objectNode();
            ok.put("ok", true);
            return ok;
        }
        ObjectNode job = F.objectNode();
        job.put("id", jobId == null ? "job_try" : jobId);
        job.put("seq", seq);
        ExpressionContext ctx = ExpressionContext.of(input == null ? F.objectNode() : input, input == null ? F.objectNode() : input,
                Map.of(), Map.of(), null, job, null, null);
        return ExpressionEngine.resolve(action.sample(), ctx);
    }

    /**
     * Answer one provider action from its sample, honouring what the world remembers and the
     * action's {@code verify} block. {@code remember} is false for dry runs so they never poison
     * the world. Throws a {@link ProviderCallException} like a real call would.
     */
    public JsonNode call(ProviderDefinition provider, Action action, JsonNode input, String jobId, int seq, boolean remember) {
        String id = idOf(input);
        String key = id == null ? null : provider.name() + ":" + id;
        Effect effect = action.effect() == null ? Effect.UNKNOWN : action.effect();
        if (key != null && effect == Effect.READ && deleted.contains(key)) {
            throw new ProviderCallException("not_found", provider.name() + " " + action.id() + ": " + id + " not found", false, 404);
        }
        JsonNode out = resolveSample(action, input, jobId, seq);
        if (key != null && effect == Effect.READ && out.isObject()) {
            ObjectNode overlay = changed.get(key);
            if (overlay != null) {
                ObjectNode o = (ObjectNode) out;
                overlay.fields().forEachRemaining(e -> {
                    if (o.has(e.getKey())) {
                        o.set(e.getKey(), e.getValue());
                    }
                });
            }
        }
        if (remember && key != null) {
            switch (effect) {
                case DELETE -> {
                    deleted.add(key);
                    changed.remove(key);
                }
                case CHANGE -> {
                    ObjectNode fields = changed.computeIfAbsent(key, k -> F.objectNode());
                    Iterator<Map.Entry<String, JsonNode>> it = input.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> e = it.next();
                        if (!"id".equals(e.getKey()) && e.getValue().isValueNode()) {
                            fields.set(e.getKey(), e.getValue());
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (remember && effect == Effect.CREATE && out.isObject() && out.hasNonNull("id")) {
            deleted.remove(provider.name() + ":" + out.get("id").asText());
        }
        if (action.verify() != null && action.verify().action() != null && effect.destructive() && remember) {
            verify(provider, action, id, jobId, seq, (ObjectNode) (out.isObject() ? out : F.objectNode()));
        }
        return out;
    }

    private void verify(ProviderDefinition provider, Action action, String id, String jobId, int seq, ObjectNode out) {
        Optional<Action> check = provider.action(action.verify().action());
        if (check.isEmpty() || id == null) {
            return;
        }
        ObjectNode input = F.objectNode();
        input.put("id", id);
        String expect = action.verify().expect() == null ? "notFound" : action.verify().expect();
        try {
            JsonNode seen = call(provider, check.get(), input, jobId, seq, false);
            if ("notFound".equalsIgnoreCase(expect)) {
                throw new ProviderCallException("verify_failed", action.id() + " on " + id + " reported success but " + check.get().id()
                        + " still finds it", false, 500);
            }
            out.put("verified", seen != null);
        } catch (ProviderCallException e) {
            if ("not_found".equals(e.errorClass()) && "notFound".equalsIgnoreCase(expect)) {
                out.put("verified", true);
                return;
            }
            throw e;
        }
    }

    public boolean isDeleted(String provider, String id) {
        return deleted.contains(provider + ":" + id);
    }

    public void forget() {
        deleted.clear();
        changed.clear();
    }

    private static String idOf(JsonNode input) {
        if (input == null || !input.isObject()) {
            return null;
        }
        JsonNode id = input.get("id");
        return id == null || !id.isValueNode() || id.isNull() ? null : id.asText();
    }

    // ---------------------------------------------------------------- automation steps

    /**
     * Answer an automation step from the provider files: ansible by repo + playbook path (or the
     * library reference {@code <provider>/<action>}), terraform by module source + action, idp by
     * tile. HTTP never touches the network ({@code { status: 200, body: {} }}), activities answer
     * {@code { ok: true, activity }}, notify {@code { delivered: true, channel, message }} and
     * JavaScript scripts run on {@link MiniScript} (scripts in other languages answer {@code {}}).
     */
    public static Answer automation(List<ProviderDefinition> providers, StepType type, JsonNode with, String jobId, int seq, boolean checkMode) {
        JsonNode w = with == null ? F.objectNode() : with;
        return switch (type) {
            case ANSIBLE -> ansible(providers, w, jobId, seq, checkMode);
            case TERRAFORM -> terraform(providers, w, jobId, seq, checkMode);
            case IDP -> idp(providers, w, jobId, seq);
            case HTTP -> {
                ObjectNode out = F.objectNode();
                out.put("status", 200);
                out.set("body", F.objectNode());
                yield new Answer(out, null, null);
            }
            case ACTIVITY -> {
                ObjectNode out = F.objectNode();
                out.put("ok", true);
                out.put("activity", w.path("activity").asText(null));
                yield new Answer(out, null, null);
            }
            case NOTIFY -> {
                ObjectNode out = F.objectNode();
                out.put("delivered", true);
                out.put("channel", w.path("channel").asText(null));
                out.put("message", w.path("message").asText(null));
                yield new Answer(out, null, null);
            }
            case SCRIPT -> new Answer("javascript".equals(w.path("language").asText("javascript"))
                    ? MiniScript.run(w.path("code").asText(null), w.get("input")) : F.objectNode(), null, null);
            default -> throw new IllegalArgumentException("Not an automation step: " + type.key());
        };
    }

    private static Answer ansible(List<ProviderDefinition> providers, JsonNode with, String jobId, int seq, boolean checkMode) {
        JsonNode pb = with.get("playbook");
        ProviderDefinition provider = null;
        Action action = null;
        String library = null;
        if (pb != null && pb.isTextual()) {
            library = pb.asText();
        } else if (pb != null && pb.isObject() && pb.hasNonNull("library")) {
            library = pb.get("library").asText();
        }
        if (library != null && library.contains("/")) {
            String pname = library.substring(0, library.indexOf('/'));
            String aname = library.substring(library.indexOf('/') + 1);
            provider = providers.stream().filter(p -> p.type() == ProviderType.ANSIBLE && p.name().equals(pname)).findFirst().orElse(null);
            action = provider == null ? null : provider.action(aname).orElse(null);
        } else if (pb != null && pb.isObject() && pb.path("git").isObject()) {
            String repo = normaliseRepo(pb.path("git").path("repo").asText(""));
            String path = pb.path("git").path("path").asText("");
            for (ProviderDefinition p : providers) {
                if (p.type() != ProviderType.ANSIBLE || p.spec().source() == null) {
                    continue;
                }
                if (!repo.equals(normaliseRepo(p.spec().source().path("git").path("repo").asText("")))) {
                    continue;
                }
                Optional<Action> a = p.actions().stream().filter(x -> path.equals(x.playbook())).findFirst();
                if (a.isPresent()) {
                    provider = p;
                    action = a.get();
                    break;
                }
            }
        }
        ObjectNode input = F.objectNode();
        if (with.path("extraVars").isObject()) {
            input.setAll((ObjectNode) with.get("extraVars"));
        }
        if (with.hasNonNull("limit")) {
            input.set("limit", with.get("limit"));
        }
        JsonNode out;
        if (action != null) {
            out = resolveSample(action, input, jobId, seq);
        } else {
            ObjectNode o = F.objectNode();
            o.put("ok", 1);
            o.put("changed", 1);
            o.put("failed", 0);
            out = o;
        }
        if (checkMode && out.isObject()) {
            ((ObjectNode) out).put("changed", 0);
            ((ObjectNode) out).put("checkMode", true);
        }
        return new Answer(out, provider, action);
    }

    private static Answer terraform(List<ProviderDefinition> providers, JsonNode with, String jobId, int seq, boolean checkMode) {
        String source = with.path("module").path("source").asText("");
        String actionId = checkMode ? "plan" : with.path("action").asText("plan");
        ProviderDefinition provider = providers.stream()
                .filter(p -> p.type() == ProviderType.TERRAFORM && p.spec().module() != null
                        && normaliseRepo(source).equals(normaliseRepo(p.spec().module().path("source").asText(""))))
                .findFirst().orElse(null);
        Action action = provider == null ? null : provider.action(actionId).orElse(null);
        JsonNode vars = with.path("vars").isObject() ? with.get("vars") : F.objectNode();
        JsonNode out;
        if (action != null) {
            out = resolveSample(action, vars, jobId, seq);
        } else {
            ObjectNode o = F.objectNode();
            o.put("add", 0);
            o.put("change", 0);
            o.put("destroy", 0);
            if (!"plan".equals(actionId)) {
                o.set("outputs", F.objectNode());
            }
            out = o;
        }
        return new Answer(out, provider, action);
    }

    private static Answer idp(List<ProviderDefinition> providers, JsonNode with, String jobId, int seq) {
        String tile = with.path("tile").asText("");
        ProviderDefinition provider = null;
        Action action = null;
        for (ProviderDefinition p : providers) {
            if (p.type() != ProviderType.IDP) {
                continue;
            }
            Optional<Action> a = p.action(tile);
            if (a.isPresent()) {
                provider = p;
                action = a.get();
                break;
            }
        }
        JsonNode input = with.path("input").isObject() ? with.get("input") : F.objectNode();
        JsonNode out;
        if (action != null) {
            out = resolveSample(action, input, jobId, seq);
        } else {
            ObjectNode o = F.objectNode();
            o.put("id", "req-" + seq);
            o.put("ticket", "IDP-" + seq);
            o.put("state", "fulfilled");
            out = o;
        }
        return new Answer(out, provider, action);
    }

    static String normaliseRepo(String repo) {
        String r = repo == null ? "" : repo.trim().toLowerCase();
        r = r.replaceFirst("^git::", "").replaceFirst("^(https?://|ssh://|git@)", "").replaceFirst("^github\\.com:", "github.com/");
        if (r.endsWith(".git")) {
            r = r.substring(0, r.length() - 4);
        }
        return r;
    }
}

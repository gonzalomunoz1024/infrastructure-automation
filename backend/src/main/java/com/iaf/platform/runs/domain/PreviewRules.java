package com.iaf.platform.runs.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.expressions.ExpressionContext;
import com.iaf.platform.common.expressions.ExpressionEngine;
import com.iaf.platform.common.expressions.ExpressionException;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Preview.Cost;
import com.iaf.platform.runs.domain.Preview.PreviewStep;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/**
 * The rules behind a preview. The static walk says what each step would do to the world (the
 * provider action's effect; terraform plan=read, apply=create, destroy=delete; ansible changes;
 * http by method; a run step carries its child's effects; register creates; use reads; the rest
 * nothing), which resources the run touches, and what the created things cost per month
 * according to the recorded samples. A dry run of the flow on the input ({@link Walk}) then
 * judges it: the steps it does not take are skipped, the plans the provider actions declare
 * ({@link PlanAnswer}) refine what the steps it takes would do, and every create call it makes
 * is priced ({@link #costParts}). Pure: the catalog, the walk and the plans are handed in.
 */
public final class PreviewRules {

    public static final int MAX_DEPTH = 3;
    public static final String CURRENCY = "USD";

    private PreviewRules() {
    }

    /** What the walk may look up. */
    public interface Catalog {
        Optional<ProviderDefinition> provider(String name);

        /** The published child a {@code run} step would start, for its version range. */
        Optional<WorkflowDefinition> child(String workflow, String range);

        /** The idp-type provider that offers a tile, when one does. */
        default Optional<Action> tile(String tile) {
            return Optional.empty();
        }
    }

    /** A resource a run touches, before the registry is asked for its name. */
    public record ResourceRef(String type, String id, String via) {
    }

    public static List<PreviewStep> steps(WorkflowDefinition def, Catalog catalog) {
        List<PreviewStep> out = new ArrayList<>();
        walk(def.steps(), "", catalog, out, 0);
        return out;
    }

    private static void walk(List<Step> steps, String prefix, Catalog catalog, List<PreviewStep> out, int depth) {
        if (steps == null) {
            return;
        }
        for (Step s : steps) {
            Effect effect = effect(s, catalog);
            List<PreviewStep> childSteps = new ArrayList<>();
            if (s.type() == com.iaf.platform.workflows.domain.StepType.RUN && depth < MAX_DEPTH) {
                Optional<WorkflowDefinition> child = catalog.child(s.withText("workflow"), s.withText("version"));
                if (child.isPresent()) {
                    walk(child.get().steps(), prefix + s.id() + "/", catalog, childSteps, depth + 1);
                    effect = strongest(childSteps);
                }
            }
            out.add(new PreviewStep(prefix + s.id(), s.displayName(), effect, detail(s, catalog)));
            out.addAll(childSteps);
            if (s.branches() != null) {
                s.branches().forEach(b -> walk(b, prefix, catalog, out, depth));
            }
            if (s.cases() != null) {
                s.cases().stream().filter(c -> c.steps() != null).forEach(c -> walk(c.steps(), prefix, catalog, out, depth));
            }
            if (s.children() != null) {
                walk(s.children(), prefix, catalog, out, depth);
            }
            if (s.catchBlock() != null && s.catchBlock().steps() != null) {
                walk(s.catchBlock().steps(), prefix, catalog, out, depth);
            }
        }
    }

    public static Effect effect(Step s, Catalog catalog) {
        return switch (s.type()) {
            case PROVIDER -> catalog.provider(s.withText("provider")).flatMap(p -> p.action(s.withText("action") == null ? "" : s.withText("action")))
                    .map(a -> a.effect() == null ? Effect.UNKNOWN : a.effect()).orElse(Effect.UNKNOWN);
            case TERRAFORM -> switch (s.withText("action") == null ? "" : s.withText("action")) {
                case "plan" -> Effect.READ;
                case "apply" -> Effect.CREATE;
                case "destroy" -> Effect.DELETE;
                default -> Effect.UNKNOWN;
            };
            case ANSIBLE -> Effect.CHANGE;
            case HTTP -> Effect.ofHttpMethod(s.withText("method"));
            case IDP -> catalog.tile(s.withText("tile") == null ? "" : s.withText("tile")).map(a -> a.effect() == null ? Effect.NONE : a.effect()).orElse(Effect.NONE);
            case REGISTER -> Effect.CREATE;
            case USE -> Effect.READ;
            case RUN -> Effect.UNKNOWN;
            default -> Effect.NONE;
        };
    }

    private static Effect strongest(List<PreviewStep> steps) {
        Effect best = Effect.NONE;
        for (PreviewStep p : steps) {
            if (rank(p.effect()) > rank(best)) {
                best = p.effect();
            }
        }
        return best;
    }

    private static int rank(Effect e) {
        return switch (e == null ? Effect.UNKNOWN : e) {
            case DELETE -> 5;
            case CREATE -> 4;
            case CHANGE -> 3;
            case READ -> 2;
            case UNKNOWN -> 1;
            case NONE -> 0;
        };
    }

    public static String detail(Step s, Catalog catalog) {
        return switch (s.type()) {
            case PROVIDER -> {
                String p = s.withText("provider");
                String a = s.withText("action");
                Optional<Action> action = catalog.provider(p == null ? "" : p).flatMap(x -> x.action(a == null ? "" : a));
                // an action without a plan may declare what it does in one line
                String declared = action.map(Action::effectDetail).filter(d -> !d.isBlank()).orElse(null);
                boolean destructive = action.map(Action::destructive).orElse(false);
                yield p + " " + a + (destructive ? " (destructive)" : "") + (declared == null ? "" : ": " + declared);
            }
            case TERRAFORM -> "terraform " + s.withText("action") + " " + (s.withNode("module") == null ? "" : s.withNode("module").path("source").asText(""));
            case ANSIBLE -> {
                JsonNode pb = s.withNode("playbook");
                String ref = pb == null ? "" : pb.isTextual() ? pb.asText() : pb.path("git").path("path").asText(pb.path("library").asText(""));
                yield "ansible " + ref + (s.with() != null && s.with().path("checkModeOnTest").asBoolean(false) ? " (check mode on test)" : "");
            }
            case HTTP -> (s.withText("method") == null ? "GET" : s.withText("method").toUpperCase(Locale.ROOT)) + " " + s.withText("url");
            case IDP -> "idp tile " + s.withText("tile");
            case ACTIVITY -> "activity " + s.withText("activity") + " on " + s.withText("taskQueue");
            case RUN -> "run " + s.withText("workflow") + (s.withText("version") == null ? "" : " " + s.withText("version"));
            case REGISTER -> "register " + s.withText("type");
            case USE -> "use " + s.withText("type");
            case APPROVAL -> "approval by " + s.withText("group");
            case NOTIFY -> "notify " + s.withText("channel");
            case WAIT -> "wait " + (s.withText("duration") == null ? "until condition" : s.withText("duration"));
            default -> s.type().key();
        };
    }

    /** The monthly cost of what the run creates, from {@code monthlyCost} in the samples of create actions; null when nothing says. */
    public static Cost cost(WorkflowDefinition def, Catalog catalog) {
        List<String> parts = new ArrayList<>();
        double total = collectCost(def.steps(), catalog, parts, 0);
        if (parts.isEmpty()) {
            return null;
        }
        return new Cost(Math.round(total * 100.0) / 100.0, CURRENCY, String.join(" + ", parts));
    }

    private static double collectCost(List<Step> steps, Catalog catalog, List<String> parts, int depth) {
        double total = 0;
        if (steps == null) {
            return 0;
        }
        for (Step s : steps) {
            if (s.type() == com.iaf.platform.workflows.domain.StepType.PROVIDER) {
                Optional<Action> a = catalog.provider(s.withText("provider") == null ? "" : s.withText("provider"))
                        .flatMap(p -> p.action(s.withText("action") == null ? "" : s.withText("action")));
                if (a.isPresent() && a.get().effect() == Effect.CREATE && a.get().sample() != null && a.get().sample().path("monthlyCost").isNumber()) {
                    double c = a.get().sample().get("monthlyCost").asDouble();
                    total += c;
                    parts.add(s.withText("provider") + " " + s.withText("action") + " " + String.format(Locale.ROOT, "%.2f", c));
                }
            } else if (s.type() == com.iaf.platform.workflows.domain.StepType.RUN && depth < MAX_DEPTH) {
                Optional<WorkflowDefinition> child = catalog.child(s.withText("workflow"), s.withText("version"));
                if (child.isPresent()) {
                    total += collectCost(child.get().steps(), catalog, parts, depth + 1);
                }
            }
            if (s.branches() != null) {
                for (List<Step> b : s.branches()) {
                    total += collectCost(b, catalog, parts, depth);
                }
            }
            if (s.cases() != null) {
                for (Step.SwitchCase c : s.cases()) {
                    if (c.steps() != null) {
                        total += collectCost(c.steps(), catalog, parts, depth);
                    }
                }
            }
            if (s.children() != null) {
                total += collectCost(s.children(), catalog, parts, depth);
            }
        }
        return total;
    }

    /** The resources a run would touch: {@code use} steps whose id resolves from the input, and {@code x-resource} inputs. */
    public static List<ResourceRef> resourceRefs(WorkflowDefinition def, JsonNode input) {
        Set<String> seen = new LinkedHashSet<>();
        List<ResourceRef> out = new ArrayList<>();
        JsonNode in = input == null ? ExpressionEngine.json(java.util.Map.of()) : input;
        JsonNode schema = def.inputSchema();
        if (schema != null) {
            schema.path("properties").properties().forEach(e -> {
                JsonNode xr = e.getValue().get("x-resource");
                JsonNode v = in.get(e.getKey());
                if (xr != null && xr.isObject() && v != null && v.isTextual() && !v.asText().isBlank()) {
                    if (seen.add(xr.path("type").asText("") + ":" + v.asText())) {
                        out.add(new ResourceRef(xr.path("type").asText(null), v.asText(), "input." + e.getKey()));
                    }
                }
            });
        }
        ExpressionContext ctx = ExpressionContext.of(in, in);
        for (Step s : def.allSteps()) {
            if (s.type() != com.iaf.platform.workflows.domain.StepType.USE || s.withText("id") == null) {
                continue;
            }
            try {
                JsonNode id = ExpressionEngine.evaluate(s.withText("id"), ctx);
                if (id.isTextual() && !id.asText().isBlank() && seen.add(s.withText("type") + ":" + id.asText())) {
                    out.add(new ResourceRef(s.withText("type"), id.asText(), "step " + s.id()));
                }
            } catch (ExpressionException ignored) {
                // the id depends on an earlier step's output: unknown until the job runs
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- the dry run

    /** A step event of the dry run, under its key: the step id, after the run steps above it ({@code deploy/createVm}). */
    public record Recorded(String key, StepEvent event) {
    }

    /** One call of a step in the dry run: its resolved parameters and its answer. */
    public record Call(JsonNode input, JsonNode output) {
    }

    /** The dry run's last word on one step, with every call of it that succeeded (a step in a {@code for} runs once per item). */
    public record Walked(String key, Step step, StepStatus status, JsonNode input, JsonNode output, List<Call> calls) {

        /** The dry run got to the step: it ran, or tried to. */
        public boolean reached() {
            return status != StepStatus.SKIPPED;
        }
    }

    /** A dry run of the flow on one input and the recorded samples; {@link #NONE} when it could not run. */
    public record Walk(boolean passed, List<Walked> steps) {

        public static final Walk NONE = new Walk(false, List.of());

        public Optional<Walked> step(String key) {
            return steps.stream().filter(w -> w.key().equals(key)).findFirst();
        }
    }

    /** The walk of the recorded events: per key the last status, the latest input and output and every succeeded call, in the order the run first got to them. */
    public static Walk walkOf(boolean passed, List<Recorded> recorded) {
        Map<String, Walked> byKey = new LinkedHashMap<>();
        Map<String, List<Call>> calls = new HashMap<>();
        for (Recorded r : recorded) {
            StepEvent e = r.event();
            Walked prev = byKey.get(r.key());
            JsonNode input = e.input() != null ? e.input() : prev == null ? null : prev.input();
            JsonNode output = e.status() == StepStatus.SUCCEEDED ? e.output() : prev == null ? null : prev.output();
            List<Call> done = calls.computeIfAbsent(r.key(), k -> new ArrayList<>());
            if (e.status() == StepStatus.SUCCEEDED) {
                done.add(new Call(input, e.output()));
            }
            byKey.put(r.key(), new Walked(r.key(), e.step(), e.status(), input, output, done));
        }
        List<Walked> out = new ArrayList<>();
        byKey.values().forEach(w -> out.add(new Walked(w.key(), w.step(), w.status(), w.input(), w.output(), List.copyOf(w.calls()))));
        return new Walk(passed, List.copyOf(out));
    }

    // ---------------------------------------------------------------- plans

    /** A provider step the dry run reached whose action names a plan: the plan action, asked with the step's input. */
    public record PlanRequest(String key, ProviderDefinition provider, Action action, Action plan, JsonNode input) {
    }

    /** What a plan answered, or how the call failed ({@code not_found}: there is nothing there). */
    public record PlanAnswer(String action, JsonNode answer, String errorClass, String message) {

        public static PlanAnswer of(String action, JsonNode answer) {
            return new PlanAnswer(action, answer, null, null);
        }

        public static PlanAnswer failed(String action, String errorClass, String message) {
            return new PlanAnswer(action, null, errorClass == null ? "error" : errorClass, message);
        }

        public boolean notFound() {
            return "not_found".equals(errorClass);
        }
    }

    /** The plans to ask: one per reached provider step whose action has a {@code plan}, with the input of its first call. */
    public static List<PlanRequest> planRequests(Walk walk, Catalog catalog) {
        List<PlanRequest> out = new ArrayList<>();
        for (Walked w : walk.steps()) {
            if (w.step().type() != StepType.PROVIDER || !w.reached() || w.input() == null) {
                continue;
            }
            Optional<ProviderDefinition> p = catalog.provider(w.input().path("provider").asText(""));
            Optional<Action> a = p.flatMap(x -> x.action(w.input().path("action").asText("")));
            Optional<Action> plan = a.filter(x -> x.plan() != null && !x.plan().isBlank()).flatMap(x -> p.get().action(x.plan()));
            if (plan.isEmpty()) {
                continue;
            }
            JsonNode in = (w.calls().isEmpty() ? w.input() : w.calls().get(0).input()).path("input");
            out.add(new PlanRequest(w.key(), p.get(), a.get(), plan.get(), in.isObject() ? in : JsonNodeFactory.instance.objectNode()));
        }
        return out;
    }

    /** What an answer says the step would do: its own {@code effect} and {@code detail}, or counts ({@code add}, {@code change}, {@code destroy}). */
    private record Reading(Effect effect, String detail) {
        boolean says() {
            return effect != null || detail != null;
        }
    }

    private static Reading reading(JsonNode answer) {
        if (answer == null || !answer.isObject()) {
            return new Reading(null, null);
        }
        Effect effect = answer.path("effect").isTextual() ? Effect.of(answer.get("effect").asText()) : null;
        String detail = answer.path("detail").isTextual() && !answer.get("detail").asText().isBlank() ? answer.get("detail").asText() : null;
        if (answer.path("add").isNumber() || answer.path("change").isNumber() || answer.path("destroy").isNumber()) {
            long add = answer.path("add").asLong(0);
            long change = answer.path("change").asLong(0);
            long destroy = answer.path("destroy").asLong(0);
            if (effect == null) {
                effect = destroy > 0 ? Effect.DELETE : add > 0 ? Effect.CREATE : change > 0 ? Effect.CHANGE : Effect.NONE;
            }
            if (detail == null) {
                detail = add + " to add, " + change + " to change, " + destroy + " to destroy";
            }
        }
        return new Reading(effect, detail);
    }

    /**
     * A step as its plan sees it. The answer may say what the step would do ({@code effect},
     * {@code detail}) or count it ({@code add}, {@code change}, {@code destroy}); for a change or a
     * delete it otherwise names what the step acts on ({@code name}, {@code id}). A plan that finds
     * nothing ({@code not_found}) leaves a change or a delete nothing to do (effect none). A failed
     * plan is noted and the effect stays.
     */
    public static PreviewStep planned(PreviewStep step, PlanAnswer plan) {
        String base = step.detail() == null ? "" : step.detail() + " · ";
        String label = plan.action() == null ? "plan" : "plan " + plan.action();
        Effect declared = step.effect();
        boolean changes = declared == Effect.CHANGE || declared == Effect.DELETE;
        if (plan.answer() == null) {
            if (plan.notFound() && changes) {
                return new PreviewStep(step.id(), step.name(), Effect.NONE, base + label + ": nothing to " + (declared == Effect.DELETE ? "delete" : "change")
                        + (plan.message() == null ? "" : " (" + plan.message() + ")"), false);
            }
            if (plan.notFound()) {
                return new PreviewStep(step.id(), step.name(), declared, base + label + ": none there yet", false);
            }
            return new PreviewStep(step.id(), step.name(), declared, base + label + " failed: " + plan.message(), false);
        }
        Reading r = reading(plan.answer());
        if (r.says()) {
            Effect effect = r.effect() == null ? declared : r.effect();
            return new PreviewStep(step.id(), step.name(), effect, base + label + ": " + (r.detail() == null ? effect.json() : r.detail()), false);
        }
        String target = changes ? target(plan.answer()) : null;
        return target == null ? step : new PreviewStep(step.id(), step.name(), declared, base + label + ": " + target, false);
    }

    private static String target(JsonNode answer) {
        String name = answer.path("name").isTextual() && !answer.get("name").asText().isBlank() ? answer.get("name").asText() : null;
        JsonNode idNode = answer.get("id");
        String id = idNode != null && idNode.isValueNode() && !idNode.isNull() && !idNode.asText().isBlank() ? idNode.asText() : null;
        if (name != null && id != null && !name.equals(id)) {
            return name + " (" + id + ")";
        }
        return name != null ? name : id;
    }

    // ---------------------------------------------------------------- the static steps judged by the dry run

    /**
     * The static steps judged by the dry run and the plans. A step the run skipped, or, when the
     * run passed, never got to, is skipped: effect none, its static detail kept. A step it took
     * keeps its effect unless its plan answered: a provider action's {@code plan}, or the answer
     * of a {@code terraform} apply or destroy, which is its plan. Steps only the run knows (the
     * build an input's {@code create} request asks for) join where the run took them, and a run
     * step's effect is the strongest of its child's steps once they are judged.
     */
    public static List<PreviewStep> refine(List<PreviewStep> steps, Walk walk, Map<String, PlanAnswer> plans, Catalog catalog) {
        Map<String, Walked> walked = new HashMap<>();
        walk.steps().forEach(w -> walked.put(w.key(), w));
        Set<String> known = new HashSet<>();
        steps.forEach(s -> known.add(s.id()));
        Map<String, List<Walked>> before = new HashMap<>();
        List<Walked> pending = new ArrayList<>();
        for (Walked w : walk.steps()) {
            if (!known.contains(w.key())) {
                pending.add(w);
            } else if (!pending.isEmpty()) {
                before.put(w.key(), List.copyOf(pending));
                pending.clear();
            }
        }
        List<PreviewStep> out = new ArrayList<>();
        for (PreviewStep s : steps) {
            before.getOrDefault(s.id(), List.of()).forEach(w -> out.add(walkOnly(w, catalog)));
            out.add(judge(s, walked.get(s.id()), walk.passed(), plans == null ? null : plans.get(s.id())));
        }
        pending.forEach(w -> out.add(walkOnly(w, catalog)));
        return runStepsCarryTheirChildren(out);
    }

    private static PreviewStep judge(PreviewStep s, Walked w, boolean passed, PlanAnswer plan) {
        if (w == null ? passed : !w.reached()) {
            return new PreviewStep(s.id(), s.name(), Effect.NONE, (s.detail() == null ? "" : s.detail() + " · ") + "not taken in the dry run", true);
        }
        if (plan != null) {
            return planned(s, plan);
        }
        if (w != null && w.step().type() == StepType.TERRAFORM && s.effect() != Effect.READ) {
            Reading r = reading(w.output());
            if (r.says()) {
                Effect effect = r.effect() == null ? s.effect() : r.effect();
                return new PreviewStep(s.id(), s.name(), effect, (s.detail() == null ? "" : s.detail() + " · ") + "plan: " + (r.detail() == null ? effect.json() : r.detail()), false);
            }
        }
        return s;
    }

    private static PreviewStep walkOnly(Walked w, Catalog catalog) {
        String detail = detail(w.step(), catalog);
        return w.reached() ? new PreviewStep(w.key(), w.step().displayName(), effect(w.step(), catalog), detail, false)
                : new PreviewStep(w.key(), w.step().displayName(), Effect.NONE, detail + " · not taken in the dry run", true);
    }

    private static List<PreviewStep> runStepsCarryTheirChildren(List<PreviewStep> steps) {
        List<PreviewStep> out = new ArrayList<>();
        for (PreviewStep s : steps) {
            List<PreviewStep> below = steps.stream().filter(c -> c.id().startsWith(s.id() + "/")).toList();
            out.add(s.skipped() || below.isEmpty() ? s : new PreviewStep(s.id(), s.name(), strongest(below), s.detail(), false));
        }
        return out;
    }

    // ---------------------------------------------------------------- cost

    /** What one thing the run creates costs per month, and who says so: a plan, a price list or the recorded sample. */
    public record CostPart(String label, double monthly, String currency, String source) {
    }

    /**
     * Every create call the run makes, priced: each call of a provider step whose effect (after
     * its plan) is create, the dry run's calls when it got to the step, a call with the static
     * input when it did not (the run failed before it). A call is priced by its plan's
     * {@code monthlyCost} (in its {@code currency}), else by the first price list that prices it,
     * else by its recorded sample's {@code monthlyCost}; unpriced calls cost nothing.
     */
    public static List<CostPart> costParts(WorkflowDefinition def, List<PreviewStep> judged, Walk walk, Map<String, PlanAnswer> plans,
            List<PriceList> lists, Catalog catalog) {
        Map<String, PreviewStep> byKey = new HashMap<>();
        judged.forEach(s -> byKey.put(s.id(), s));
        List<CostPart> out = new ArrayList<>();
        Set<String> walkedKeys = new HashSet<>();
        for (Walked w : walk.steps()) {
            walkedKeys.add(w.key());
            PreviewStep s = byKey.get(w.key());
            if (w.step().type() != StepType.PROVIDER || !w.reached() || w.input() == null || s == null || s.effect() != Effect.CREATE) {
                continue;
            }
            String provider = w.input().path("provider").asText("");
            String action = w.input().path("action").asText("");
            PlanAnswer plan = plans == null ? null : plans.get(w.key());
            List<Call> calls = w.calls().isEmpty() ? List.of(new Call(w.input(), null)) : w.calls();
            for (Call c : calls) {
                price(provider, action, c.input() == null ? null : c.input().path("input"), c.output(), plan, lists).ifPresent(out::add);
            }
        }
        if (!walk.passed()) {
            visit(def.steps(), "", catalog, 0, (key, s) -> {
                PreviewStep judgedStep = byKey.get(key);
                if (s.type() != StepType.PROVIDER || walkedKeys.contains(key) || judgedStep == null || judgedStep.effect() != Effect.CREATE) {
                    return;
                }
                String provider = s.withText("provider");
                String action = s.withText("action");
                Optional<Action> a = catalog.provider(provider == null ? "" : provider).flatMap(p -> p.action(action == null ? "" : action));
                price(provider, action, s.withNode("input"), a.map(Action::sample).orElse(null), null, lists).ifPresent(out::add);
            });
        }
        return out;
    }

    static Optional<CostPart> price(String provider, String action, JsonNode input, JsonNode answer, PlanAnswer plan, List<PriceList> lists) {
        String label = provider + " " + action;
        if (plan != null && plan.answer() != null && plan.answer().path("monthlyCost").isNumber()) {
            return Optional.of(new CostPart(label, plan.answer().get("monthlyCost").asDouble(), plan.answer().path("currency").asText(CURRENCY), "plan " + plan.action()));
        }
        Optional<PriceList.Quote> q = PriceList.quote(lists, provider, action, input);
        if (q.isPresent()) {
            return Optional.of(new CostPart(label, q.get().monthly(), q.get().currency(), "price list " + q.get().list()));
        }
        if (answer != null && answer.path("monthlyCost").isNumber()) {
            return Optional.of(new CostPart(label, answer.get("monthlyCost").asDouble(), answer.path("currency").asText(CURRENCY), "sample"));
        }
        return Optional.empty();
    }

    /**
     * The monthly cost of the parts, times {@code rows} for a batch (every row is priced like the
     * first). The total is in the first part's currency; parts in another currency are named in
     * the detail, not added. Null when nothing is priced.
     */
    public static Cost total(List<CostPart> parts, int rows) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        String currency = parts.get(0).currency();
        Map<CostPart, Integer> counted = new LinkedHashMap<>();
        parts.forEach(p -> counted.merge(p, 1, Integer::sum));
        double sum = 0;
        List<String> added = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (Map.Entry<CostPart, Integer> e : counted.entrySet()) {
            CostPart p = e.getKey();
            String text = p.label() + " " + String.format(Locale.ROOT, "%.2f", p.monthly()) + (e.getValue() > 1 ? " x " + e.getValue() : "") + " (" + p.source() + ")";
            if (p.currency().equals(currency)) {
                sum += p.monthly() * e.getValue();
                added.add(text);
            } else {
                other.add(text + " " + p.currency());
            }
        }
        int n = Math.max(1, rows);
        String detail = String.join(" + ", added) + (n > 1 ? " x " + n + " rows" : "") + (other.isEmpty() ? "" : "; not added: " + String.join(", ", other));
        return new Cost(Math.round(sum * n * 100.0) / 100.0, currency, detail);
    }

    // ---------------------------------------------------------------- what the run touches

    /**
     * The resources the {@code use} steps the dry run got to read (ids an earlier step's output
     * decides included), but not what the dry run itself registered (an input's build).
     */
    public static List<ResourceRef> usedRefs(Walk walk) {
        List<ResourceRef> out = new ArrayList<>();
        for (Walked w : walk.steps()) {
            boolean dryBuilt = w.output() != null && w.output().path("dryRun").asBoolean(false);
            if (w.step().type() == StepType.USE && w.reached() && !dryBuilt && w.input() != null && w.input().path("id").isTextual()
                    && !w.input().get("id").asText().isBlank()) {
                out.add(new ResourceRef(w.input().path("type").asText(null), w.input().get("id").asText(), "step " + w.key()));
            }
        }
        return out;
    }

    /**
     * The resources among {@code ids} that the run changes or deletes, whose dependents it touches
     * too: those a change or delete step the dry run took names in its parameters; all of them when
     * such a step was not reached (the run failed before it, so its parameters are unknown).
     */
    public static Set<String> changed(List<PreviewStep> judged, Walk walk, Set<String> ids) {
        Map<String, Walked> walked = new HashMap<>();
        walk.steps().forEach(w -> walked.put(w.key(), w));
        Set<String> out = new LinkedHashSet<>();
        for (PreviewStep s : judged) {
            if (s.skipped() || (s.effect() != Effect.CHANGE && s.effect() != Effect.DELETE)) {
                continue;
            }
            Walked w = walked.get(s.id());
            if (w == null || !w.reached()) {
                return new LinkedHashSet<>(ids);
            }
            Consumer<String> seen = t -> {
                if (ids.contains(t)) {
                    out.add(t);
                }
            };
            texts(w.input(), seen, 0);
            w.calls().forEach(c -> texts(c.input(), seen, 0));
        }
        return out;
    }

    private static void texts(JsonNode n, Consumer<String> each, int depth) {
        if (n == null || depth > 8) {
            return;
        }
        if (n.isTextual()) {
            each.accept(n.asText());
        } else if (n.isContainerNode()) {
            n.forEach(c -> texts(c, each, depth + 1));
        }
    }

    /** Every step of the static walk under its key, children of run steps included, in the walk's order. */
    private static void visit(List<Step> steps, String prefix, Catalog catalog, int depth, BiConsumer<String, Step> each) {
        if (steps == null) {
            return;
        }
        for (Step s : steps) {
            each.accept(prefix + s.id(), s);
            if (s.type() == StepType.RUN && depth < MAX_DEPTH) {
                catalog.child(s.withText("workflow"), s.withText("version"))
                        .ifPresent(child -> visit(child.steps(), prefix + s.id() + "/", catalog, depth + 1, each));
            }
            if (s.branches() != null) {
                s.branches().forEach(b -> visit(b, prefix, catalog, depth, each));
            }
            if (s.cases() != null) {
                s.cases().stream().filter(c -> c.steps() != null).forEach(c -> visit(c.steps(), prefix, catalog, depth, each));
            }
            if (s.children() != null) {
                visit(s.children(), prefix, catalog, depth, each);
            }
            if (s.catchBlock() != null && s.catchBlock().steps() != null) {
                visit(s.catchBlock().steps(), prefix, catalog, depth, each);
            }
        }
    }


    /** A catalog over lists already in hand. */
    public static Catalog catalog(List<ProviderDefinition> providers, java.util.function.BiFunction<String, String, Optional<WorkflowDefinition>> children) {
        return new Catalog() {
            @Override
            public Optional<ProviderDefinition> provider(String name) {
                return providers.stream().filter(p -> p.name().equals(name)).findFirst();
            }

            @Override
            public Optional<WorkflowDefinition> child(String workflow, String range) {
                return workflow == null ? Optional.empty() : children.apply(workflow, range);
            }

            @Override
            public Optional<Action> tile(String tile) {
                return providers.stream().filter(p -> p.type() == ProviderType.IDP).flatMap(p -> p.action(tile).stream()).findFirst();
            }
        };
    }
}

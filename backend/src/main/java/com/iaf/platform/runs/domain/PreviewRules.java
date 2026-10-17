package com.iaf.platform.runs.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.expressions.ExpressionContext;
import com.iaf.platform.common.expressions.ExpressionEngine;
import com.iaf.platform.common.expressions.ExpressionException;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.runs.domain.Preview.Cost;
import com.iaf.platform.runs.domain.Preview.PreviewStep;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/**
 * The static walk behind a preview: what each step would do to the world (the provider action's
 * effect; terraform plan=read, apply=create, destroy=delete; ansible changes; http by method; a
 * run step carries its child's effects; register creates; use reads; the rest nothing), which
 * resources the run touches, and what the created things cost per month according to the
 * recorded samples. Pure: the catalog is handed in, already resolved.
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
                boolean destructive = catalog.provider(p == null ? "" : p).flatMap(x -> x.action(a == null ? "" : a)).map(Action::destructive).orElse(false);
                yield p + " " + a + (destructive ? " (destructive)" : "");
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
            schema.path("properties").fields().forEachRemaining(e -> {
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

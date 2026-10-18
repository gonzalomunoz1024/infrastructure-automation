package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.iaf.platform.workflows.domain.Step.SwitchCase;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Trigger;

/**
 * A workflow as a Mermaid {@code flowchart LR}: the preview of a change request, which GitHub
 * draws in the pull request and the change request page shows beside the diff.
 *
 * <p>The ways in (the API operation, the MCP tool, each trigger) lead to the first step. Every
 * step is one node, or one subgraph for a container, labelled with its name and its type (a
 * provider call adds the provider and action, a run step the workflow and range). A fork is a
 * subgraph with one subgraph per branch, a for loop and a try are subgraphs around their steps,
 * and a switch is a decision whose edges carry the case: a case with steps leads to a subgraph
 * of them, a case that jumps leads to the step it names or to End, and a switch with no default
 * also goes on to the next step. Dotted edges lead to what runs on an error: a try's catch, the
 * workflow's catch (its steps then sit in a subgraph of their own) and a step's compensation. A
 * raise leads nowhere.
 *
 * <p>Node ids are generated ({@code s1}, {@code s2}, ... in document order), so no step id can
 * read as Mermaid syntax, and every label is quoted with the characters Mermaid would read as
 * syntax written as entity codes ({@link #escape}).
 */
public final class WorkflowMermaid {

    /** How much of a switch case's condition its edge shows. */
    static final int CONDITION = 60;
    /** The line break between a step's name and its type. */
    static final String BREAK = "<br/>";

    private WorkflowMermaid() {
    }

    /** The flowchart of a workflow. */
    public static String of(WorkflowDefinition def) {
        return new Chart(def).draw();
    }

    /** A change request's preview: the flowchart of the workflow it writes; empty for a provider or a file that does not parse. */
    public static Optional<String> preview(ChangeRequest cr) {
        if (cr == null || cr.kind() != ChangeRequest.Kind.WORKFLOW || cr.yaml() == null || cr.yaml().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(of(WorkflowYaml.parse(cr.yaml())));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Text as it may stand between the double quotes of a Mermaid label: on one line, with
     * {@code " # & < > ` | %} written as entity codes ({@code #34;} for a double quote), so a name
     * can neither end its label nor start an entity, a directive, a markdown string or HTML.
     */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}\\s]+", " ").trim();
        StringBuilder sb = new StringBuilder(flat.length());
        for (int i = 0; i < flat.length(); i++) {
            char c = flat.charAt(i);
            switch (c) {
                case '"', '#', '&', '<', '>', '`', '|', '%' -> sb.append('#').append((int) c).append(';');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** What a step's label says under its name: its type, and for a provider call or a run step what it calls. */
    static String kind(Step s) {
        String type = s.type() == null ? "step" : s.type().key();
        if (s.type() == StepType.PROVIDER && s.withText("provider") != null && s.withText("action") != null) {
            return type + ": " + s.withText("provider") + " " + s.withText("action");
        }
        if (s.type() == StepType.RUN && s.withText("workflow") != null) {
            return type + ": " + s.withText("workflow") + (s.withText("version") == null ? "" : " " + s.withText("version"));
        }
        return type;
    }

    /** A way in other than the API and MCP, as its node says it. */
    static String trigger(Trigger t) {
        JsonNode spec = t.spec() == null ? MissingNode.getInstance() : t.spec();
        String type = t.type() == null || t.type().isBlank() ? "trigger" : t.type();
        return switch (type) {
            case "alert" -> {
                String source = spec.path("match").path("source").asText("");
                String alert = spec.path("match").path("labels").path("alertname").asText("");
                yield "alert" + (source.isBlank() ? "" : " from " + source) + (alert.isBlank() ? "" : ": " + alert);
            }
            case "event" -> {
                String topic = spec.path("topic").asText("");
                yield "event" + (topic.isBlank() ? "" : " " + topic);
            }
            default -> type;
        };
    }

    /** A switch case's condition without its braces, cut to {@link #CONDITION} characters. */
    static String condition(String when) {
        if (when == null || when.isBlank()) {
            return "case";
        }
        String text = when.trim();
        if (text.startsWith("{{") && text.endsWith("}}") && text.length() >= 4) {
            text = text.substring(2, text.length() - 2).trim();
        }
        return text.length() <= CONDITION ? text : text.substring(0, CONDITION - 1) + "…";
    }

    static String edge(String from, String label, String to, boolean dotted) {
        return from + (dotted ? " -.->" : " -->") + (label == null ? "" : "|\"" + escape(label) + "\"|") + " " + to;
    }

    /** Where the flow goes on from: a node or subgraph, and the label of the edge that leaves it. */
    private record Exit(String from, String label) {
    }

    /** A drawn step or run of steps: where edges into it lead and what leaves it (nothing after a raise). */
    private record Drawn(String entry, List<Exit> exits) {
    }

    /** A switch case that jumps to a step id or to {@code end}, linked once every step has its node. */
    private record Jump(String from, String label, String target) {
    }

    /** One drawing: nodes and subgraphs first, so each node is declared where it belongs, then the edges. */
    private static final class Chart {

        private final WorkflowDefinition def;
        private final StringBuilder body = new StringBuilder();
        private final List<String> edges = new ArrayList<>();
        private final Map<String, String> nodes = new HashMap<>();
        private final List<Jump> jumps = new ArrayList<>();
        private int count;

        Chart(WorkflowDefinition def) {
            this.def = def;
        }

        String draw() {
            List<String> ways = new ArrayList<>();
            ways.add(def.method() + " " + def.path());
            if (def.mcpExposed()) {
                ways.add("MCP tool " + def.mcpTool());
            }
            for (Trigger t : def.triggers() == null ? List.<Trigger>of() : def.triggers()) {
                ways.add(trigger(t));
            }
            for (int i = 0; i < ways.size(); i++) {
                line(1, "t" + i + "([\"" + escape(ways.get(i)) + "\"])");
            }
            List<Step> recovery = def.workflow() == null || def.workflow().catchFlow() == null || def.workflow().catchFlow().steps() == null
                    ? List.of() : def.workflow().catchFlow().steps();
            Drawn main;
            if (recovery.isEmpty()) {
                main = chain(def.steps(), 1);
            } else {
                line(1, "subgraph main [\"do\"]");
                main = chain(def.steps(), 2);
                line(1, "end");
                line(1, "subgraph onError [\"catch\"]");
                chain(recovery, 2);
                line(1, "end");
                edges.add(edge("main", "on error", "onError", true));
            }
            boolean end = false;
            List<String> jumped = new ArrayList<>();
            for (Jump j : jumps) {
                String to = "end".equals(j.target()) ? "done" : nodes.get(j.target());
                if (to != null) {
                    end |= "done".equals(to);
                    jumped.add(edge(j.from(), j.label(), to, false));
                }
            }
            if (end) {
                line(1, "done([\"End\"])");
            }
            StringBuilder out = new StringBuilder("flowchart LR\n").append(body);
            if (main.entry() != null) {
                for (int i = 0; i < ways.size(); i++) {
                    out.append("  ").append(edge("t" + i, null, main.entry(), false)).append('\n');
                }
            }
            edges.forEach(e -> out.append("  ").append(e).append('\n'));
            jumped.forEach(e -> out.append("  ").append(e).append('\n'));
            return out.toString();
        }

        private void line(int depth, String text) {
            body.append("  ".repeat(depth)).append(text).append('\n');
        }

        /** Steps that run one after the other, each linked from what left the one before. */
        private Drawn chain(List<Step> steps, int depth) {
            String entry = null;
            List<Exit> exits = null;
            for (Step s : steps == null ? List.<Step>of() : steps) {
                Drawn d = step(s, depth);
                if (exits == null) {
                    entry = d.entry();
                } else {
                    exits.forEach(e -> edges.add(edge(e.from(), e.label(), d.entry(), false)));
                }
                exits = d.exits();
            }
            return new Drawn(entry, exits == null ? List.of() : exits);
        }

        private Drawn step(Step s, int depth) {
            String id = "s" + (++count);
            if (s.id() != null) {
                nodes.putIfAbsent(s.id(), id);
            }
            String label = escape(s.displayName()) + BREAK + escape(kind(s));
            StepType type = s.type();
            Drawn drawn;
            if (type == StepType.FORK) {
                line(depth, "subgraph " + id + " [\"" + label + "\"]");
                List<List<Step>> branches = s.branches() == null ? List.of() : s.branches();
                for (int i = 0; i < branches.size(); i++) {
                    line(depth + 1, "subgraph " + id + "b" + (i + 1) + " [\"branch " + (i + 1) + "\"]");
                    chain(branches.get(i), depth + 2);
                    line(depth + 1, "end");
                }
                line(depth, "end");
                drawn = new Drawn(id, List.of(new Exit(id, null)));
            } else if (type == StepType.FOR || type == StepType.TRY) {
                line(depth, "subgraph " + id + " [\"" + label + "\"]");
                chain(s.children(), depth + 1);
                line(depth, "end");
                List<Exit> exits = new ArrayList<>(List.of(new Exit(id, null)));
                if (type == StepType.TRY && s.catchBlock() != null && s.catchBlock().steps() != null && !s.catchBlock().steps().isEmpty()) {
                    String caught = id + "c";
                    line(depth, "subgraph " + caught + " [\"catch\"]");
                    Drawn recovery = chain(s.catchBlock().steps(), depth + 1);
                    line(depth, "end");
                    edges.add(edge(id, "on error", caught, true));
                    exits.addAll(recovery.exits());
                }
                drawn = new Drawn(id, exits);
            } else if (type == StepType.SWITCH) {
                line(depth, id + "{\"" + label + "\"}");
                drawn = new Drawn(id, cases(s, id, depth));
            } else {
                line(depth, id + (type == StepType.APPROVAL ? "{{\"" + label + "\"}}" : "[\"" + label + "\"]"));
                drawn = new Drawn(id, type == StepType.RAISE ? List.of() : List.of(new Exit(id, null)));
            }
            if (s.compensate() != null && !s.compensate().isEmpty()) {
                String undo = id + "k";
                line(depth, "subgraph " + undo + " [\"compensate\"]");
                chain(s.compensate(), depth + 1);
                line(depth, "end");
                edges.add(edge(id, "compensate", undo, true));
            }
            return drawn;
        }

        /** A switch's cases: a subgraph per case with steps, a jump per case with {@code then}; what goes on to the next step. */
        private List<Exit> cases(Step s, String id, int depth) {
            List<Exit> exits = new ArrayList<>();
            List<SwitchCase> cases = s.cases() == null ? List.of() : s.cases();
            boolean fallback = false;
            for (int i = 0; i < cases.size(); i++) {
                SwitchCase c = cases.get(i);
                String when = c.isDefault() ? "default" : condition(c.when());
                fallback |= c.isDefault();
                if (c.steps() != null && !c.steps().isEmpty()) {
                    String branch = id + "c" + (i + 1);
                    line(depth, "subgraph " + branch + " [\"" + (c.isDefault() ? "default" : "case " + (i + 1)) + "\"]");
                    Drawn inner = chain(c.steps(), depth + 1);
                    line(depth, "end");
                    edges.add(edge(id, when, branch, false));
                    exits.addAll(inner.exits());
                } else if (c.steps() == null && c.then() != null && !c.then().isBlank()) {
                    jumps.add(new Jump(id, when, c.then()));
                } else {
                    exits.add(new Exit(id, when));
                }
            }
            if (!fallback) {
                exits.add(new Exit(id, "otherwise"));
            }
            return exits;
        }
    }
}

package com.iaf.platform.runs.adapters.outbound.live;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;

/**
 * What ansible says about a run, read from ansible-runner's JSON events (one per stdout line) or
 * from the document of the JSON stdout callback: the play recap per host, the data playbooks
 * publish with {@code set_stats}, and the failures worth naming. The output of a step is
 * {@code { ok, changed, failed, unreachable, skipped, hosts: { <host>: { ok, changed,
 * unreachable, failed, skipped } }, stats }} with every {@code set_stats} key also at the top
 * level (unless it is one of those names).
 */
final class AnsibleEvents {

    static final Set<String> RESERVED = Set.of("ok", "changed", "failed", "unreachable", "skipped", "hosts", "stats", "checkMode");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final int NAMED = 3;

    /** One host's line of the play recap. */
    record Recap(int ok, int changed, int unreachable, int failed, int skipped) {
    }

    record Failure(String host, String task, String message, boolean unreachable) {
    }

    private final Function<String, String> mask;
    private final Map<String, Recap> hosts = new LinkedHashMap<>();
    private final List<Failure> failures = new ArrayList<>();
    private final Deque<String> errors = new ArrayDeque<>();
    private ObjectNode stats = F.objectNode();
    private boolean finished;

    AnsibleEvents(Function<String, String> mask) {
        this.mask = mask == null ? Function.identity() : mask;
    }

    /** One stdout line of {@code ansible-runner run --json}; answers what is worth a line in the job log, or null. */
    synchronized String event(String line) {
        String t = line == null ? "" : line.strip();
        if (t.isEmpty()) {
            return null;
        }
        JsonNode e = null;
        if (t.startsWith("{")) {
            try {
                e = JSON.readTree(t);
            } catch (IOException ex) {
                e = null;
            }
        }
        if (e == null || !e.has("event")) {
            error(t);
            return mask.apply(t);
        }
        JsonNode d = e.path("event_data");
        String host = d.path("host").asText("?");
        String task = d.path("task").asText("");
        return switch (e.path("event").asText()) {
            case "playbook_on_play_start" -> "PLAY [" + mask.apply(d.path("name").asText(d.path("play").asText(""))) + "]";
            case "playbook_on_task_start" -> "TASK [" + mask.apply(task) + "]";
            case "runner_on_failed" -> {
                String msg = message(d.path("res"));
                if (d.path("ignore_errors").asBoolean(false)) {
                    yield "failed (ignored): [" + host + "] " + msg;
                }
                failures.add(new Failure(host, task, msg, false));
                yield "failed: [" + host + "] " + msg;
            }
            case "runner_on_unreachable" -> {
                String msg = message(d.path("res"));
                failures.add(new Failure(host, task, msg, true));
                yield "unreachable: [" + host + "] " + msg;
            }
            case "runner_on_ok" -> d.path("res").path("changed").asBoolean(false) ? "changed: [" + host + "]" : null;
            case "playbook_on_stats" -> {
                recap(d);
                yield null;
            }
            case "verbose", "error", "warning", "deprecated" -> {
                String text = e.path("stdout").asText("").strip();
                if (text.contains("ERROR")) {
                    error(text);
                }
                yield text.isEmpty() ? null : mask.apply(text);
            }
            default -> null;
        };
    }

    /** The whole document of the {@code json} stdout callback ({@code ansible-playbook}). */
    synchronized void document(JsonNode doc) {
        for (JsonNode play : doc.path("plays")) {
            for (JsonNode task : play.path("tasks")) {
                String name = task.path("task").path("name").asText("");
                Iterator<Map.Entry<String, JsonNode>> it = task.path("hosts").properties().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> h = it.next();
                    JsonNode res = h.getValue();
                    if (res.path("unreachable").asBoolean(false)) {
                        failures.add(new Failure(h.getKey(), name, message(res), true));
                    } else if (res.path("failed").asBoolean(false)) {
                        failures.add(new Failure(h.getKey(), name, message(res), false));
                    }
                }
            }
        }
        Iterator<Map.Entry<String, JsonNode>> it = doc.path("stats").properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> h = it.next();
            JsonNode s = h.getValue();
            hosts.put(h.getKey(), new Recap(s.path("ok").asInt(), s.path("changed").asInt(), s.path("unreachable").asInt(),
                    s.path("failures").asInt(), s.path("skipped").asInt()));
        }
        if (doc.path("global_custom_stats").isObject()) {
            stats = (ObjectNode) doc.get("global_custom_stats").deepCopy();
        }
        finished = true;
    }

    /** The task names of the document, for the job log after an {@code ansible-playbook} run. */
    static List<String> tasks(JsonNode doc) {
        List<String> out = new ArrayList<>();
        for (JsonNode play : doc.path("plays")) {
            out.add("PLAY [" + play.path("play").path("name").asText("") + "]");
            for (JsonNode task : play.path("tasks")) {
                out.add("TASK [" + task.path("task").path("name").asText("") + "]");
            }
        }
        return out;
    }

    private void recap(JsonNode d) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (String counter : List.of("processed", "ok", "changed", "dark", "failures", "skipped")) {
            d.path(counter).fieldNames().forEachRemaining(names::add);
        }
        for (String h : names) {
            hosts.put(h, new Recap(d.path("ok").path(h).asInt(), d.path("changed").path(h).asInt(), d.path("dark").path(h).asInt(),
                    d.path("failures").path(h).asInt(), d.path("skipped").path(h).asInt()));
        }
        if (d.path("artifact_data").isObject()) {
            stats = (ObjectNode) d.get("artifact_data").deepCopy();
        }
        finished = true;
    }

    private void error(String text) {
        errors.addLast(text.length() > 500 ? text.substring(0, 500) + "…" : text);
        if (errors.size() > 10) {
            errors.removeFirst();
        }
    }

    private String message(JsonNode res) {
        String m = res.path("msg").asText("");
        if (m.isBlank()) {
            m = res.path("stderr").asText("");
        }
        if (m.isBlank()) {
            m = res.path("module_stderr").asText("");
        }
        m = m.strip().replaceAll("\\s+", " ");
        if (m.isEmpty()) {
            m = "no message";
        }
        return mask.apply(m.length() > 300 ? m.substring(0, 300) + "…" : m);
    }

    synchronized boolean finished() {
        return finished;
    }

    synchronized Map<String, Recap> hosts() {
        return Map.copyOf(hosts);
    }

    /** The recap lines for the job log. */
    synchronized List<String> recapLines() {
        List<String> out = new ArrayList<>();
        hosts.forEach((h, r) -> out.add(h + ": ok=" + r.ok() + " changed=" + r.changed() + " unreachable=" + r.unreachable()
                + " failed=" + r.failed() + " skipped=" + r.skipped()));
        return out;
    }

    synchronized ObjectNode output(boolean checkMode) {
        int ok = 0;
        int changed = 0;
        int unreachable = 0;
        int failed = 0;
        int skipped = 0;
        ObjectNode perHost = F.objectNode();
        for (Map.Entry<String, Recap> e : hosts.entrySet()) {
            Recap r = e.getValue();
            ok += r.ok();
            changed += r.changed();
            unreachable += r.unreachable();
            failed += r.failed();
            skipped += r.skipped();
            ObjectNode h = perHost.putObject(e.getKey());
            h.put("ok", r.ok());
            h.put("changed", r.changed());
            h.put("unreachable", r.unreachable());
            h.put("failed", r.failed());
            h.put("skipped", r.skipped());
        }
        ObjectNode out = F.objectNode();
        out.put("ok", ok);
        out.put("changed", changed);
        out.put("failed", failed);
        out.put("unreachable", unreachable);
        out.put("skipped", skipped);
        out.set("hosts", perHost);
        Iterator<Map.Entry<String, JsonNode>> it = stats.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!RESERVED.contains(e.getKey())) {
                out.set(e.getKey(), e.getValue());
            }
        }
        out.set("stats", stats);
        if (checkMode) {
            out.put("checkMode", true);
        }
        return out;
    }

    /**
     * The step's error after a run that did not succeed, or null when it did: failed hosts first,
     * then unreachable ones, then a non-zero exit with what ansible printed last.
     */
    synchronized ProviderCallException failure(int exitCode, List<String> stderrTail) {
        List<String> failedHosts = hosts.entrySet().stream().filter(e -> e.getValue().failed() > 0).map(Map.Entry::getKey).toList();
        List<String> darkHosts = hosts.entrySet().stream().filter(e -> e.getValue().unreachable() > 0).map(Map.Entry::getKey).toList();
        if (finished && !failedHosts.isEmpty()) {
            return new ProviderCallException("failed", count(failedHosts.size()) + " failed: " + name(failedHosts, false), false, 0);
        }
        if (finished && !darkHosts.isEmpty()) {
            return new ProviderCallException("unreachable", count(darkHosts.size()) + " unreachable: " + name(darkHosts, true), true, 0);
        }
        if (exitCode == 0) {
            return null;
        }
        String why = !errors.isEmpty() ? errors.peekLast() : stderrTail == null || stderrTail.isEmpty() ? "" : stderrTail.get(stderrTail.size() - 1);
        why = mask.apply(why == null ? "" : why.strip());
        return new ProviderCallException("failed", "ansible exited with " + exitCode + (why.isEmpty() ? "" : ": " + why), false, 0);
    }

    private static String count(int n) {
        return n == 1 ? "1 host" : n + " hosts";
    }

    private String name(List<String> names, boolean unreachable) {
        List<String> parts = new ArrayList<>();
        for (String h : names.subList(0, Math.min(NAMED, names.size()))) {
            Failure f = failures.stream().filter(x -> x.host().equals(h) && x.unreachable() == unreachable).reduce((a, b) -> b).orElse(null);
            parts.add(f == null ? h : h + " (" + (f.task().isBlank() ? "" : "task '" + mask.apply(f.task()) + "': ") + f.message() + ")");
        }
        String s = String.join("; ", parts);
        return names.size() > NAMED ? s + "; and " + (names.size() - NAMED) + " more" : s;
    }
}

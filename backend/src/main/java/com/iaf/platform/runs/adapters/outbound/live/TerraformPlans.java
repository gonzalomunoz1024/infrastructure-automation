package com.iaf.platform.runs.adapters.outbound.live;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What terraform says, read without running it: where a module source points, the counts of a
 * plan ({@code terraform show -json}), the outputs ({@code terraform output -json}, sensitive
 * values withheld) and the lines of its {@code -json} UI stream worth a job log line.
 */
final class TerraformPlans {

    static final String SENSITIVE = "(sensitive)";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Set<String> LOGGED = Set.of("planned_change", "change_summary", "apply_start", "apply_complete", "apply_errored", "resource_drift");

    private TerraformPlans() {
    }

    /** A git module: the repository, the directory inside it ("" for the root) and the ref the source pins, if any. */
    record ModuleSource(String repo, String subdir, String ref) {
    }

    /**
     * {@code git::https://host/repo//sub?ref=v1}, {@code git::https://host/repo.git}, or the
     * {@code github.com/org/repo//sub} and {@code bitbucket.org/...} shorthands. Registry
     * addresses, local paths, HTTP archives and other source types are refused with the reason.
     */
    static ModuleSource source(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("module.source is missing");
        }
        if (s.startsWith("./") || s.startsWith("../") || s.startsWith("/")) {
            throw new IllegalArgumentException("A local module path cannot run on the platform; use a git source like git::https://host/repo//dir");
        }
        boolean git = false;
        if (s.startsWith("git::")) {
            s = s.substring(5);
            git = true;
        } else if (s.matches("^[a-z0-9]+::.*")) {
            throw new IllegalArgumentException("Module sources of type " + s.substring(0, s.indexOf("::")) + ":: are not supported; use git::https://");
        }
        String ref = null;
        int q = s.indexOf('?');
        if (q >= 0) {
            for (String pair : s.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals("ref")) {
                    ref = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            s = s.substring(0, q);
        }
        int schemeEnd = s.indexOf("://");
        int dbl = s.indexOf("//", schemeEnd >= 0 ? schemeEnd + 3 : 0);
        String repo = dbl >= 0 ? s.substring(0, dbl) : s;
        String subdir = dbl >= 0 ? s.substring(dbl + 2) : "";
        if (schemeEnd < 0) {
            String lower = repo.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("github.com/") && !lower.startsWith("bitbucket.org/") && !git) {
                throw new IllegalArgumentException("Registry module sources (" + raw.trim() + ") are not supported yet; use a git source like git::https://host/repo//dir");
            }
            if (lower.startsWith("git@")) {
                throw new IllegalArgumentException("SSH module sources are not supported; use git::https://");
            }
        } else {
            String scheme = s.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("file")) {
                throw new IllegalArgumentException("Module sources over " + scheme + ":// are not supported; use git::https://");
            }
            if (!git && !repo.endsWith(".git")) {
                throw new IllegalArgumentException("A plain https module source is an HTTP archive, which is not supported; use git::" + raw.trim());
            }
        }
        if (subdir.startsWith("/") || subdir.contains("..")) {
            throw new IllegalArgumentException("The module's directory '" + subdir + "' is not a plain path inside the repository");
        }
        return new ModuleSource(repo, subdir.endsWith("/") ? subdir.substring(0, subdir.length() - 1) : subdir, ref);
    }

    record Summary(int add, int change, int destroy) {
    }

    /** Counts as terraform prints them: a replacement is one add and one destroy; data reads and no-ops do not count. */
    static Summary summary(JsonNode plan) {
        int add = 0;
        int change = 0;
        int destroy = 0;
        for (JsonNode rc : plan.path("resource_changes")) {
            if ("data".equals(rc.path("mode").asText())) {
                continue;
            }
            boolean create = false;
            boolean delete = false;
            boolean update = false;
            for (JsonNode a : rc.path("change").path("actions")) {
                switch (a.asText()) {
                    case "create" -> create = true;
                    case "delete" -> delete = true;
                    case "update" -> update = true;
                    default -> {
                    }
                }
            }
            add += create ? 1 : 0;
            destroy += delete ? 1 : 0;
            change += update && !create && !delete ? 1 : 0;
        }
        return new Summary(add, change, destroy);
    }

    /** {@code { name: value }}; a sensitive output's value is never handed out. */
    static ObjectNode outputs(JsonNode outputJson) {
        ObjectNode out = F.objectNode();
        Iterator<Map.Entry<String, JsonNode>> it = outputJson.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode o = e.getValue();
            if (o.path("sensitive").asBoolean(false)) {
                out.put(e.getKey(), SENSITIVE);
            } else {
                out.set(e.getKey(), o.has("value") ? o.get("value") : F.nullNode());
            }
        }
        return out;
    }

    /** A line of terraform's {@code -json} UI stream: what goes to the job log (or null) and, for an error diagnostic, the error. */
    record Ui(String log, String error) {
    }

    static Ui ui(String line) {
        String t = line == null ? "" : line.strip();
        if (t.isEmpty()) {
            return new Ui(null, null);
        }
        if (!t.startsWith("{")) {
            return new Ui(t, null);
        }
        JsonNode n;
        try {
            n = JSON.readTree(t);
        } catch (IOException e) {
            return new Ui(t, null);
        }
        String type = n.path("type").asText("");
        String message = n.path("@message").asText("");
        if (type.equals("diagnostic")) {
            JsonNode d = n.path("diagnostic");
            if ("error".equals(d.path("severity").asText(n.path("@level").asText()))) {
                String detail = d.path("detail").asText("").strip().replaceAll("\\s+", " ");
                String summary = d.path("summary").asText(message);
                String error = summary + (detail.isEmpty() ? "" : ": " + (detail.length() > 300 ? detail.substring(0, 300) + "…" : detail));
                return new Ui(message, error);
            }
            return new Ui(message, null);
        }
        return new Ui(LOGGED.contains(type) && !message.isBlank() ? message : null, null);
    }
}

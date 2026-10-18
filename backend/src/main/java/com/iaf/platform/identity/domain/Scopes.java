package com.iaf.platform.identity.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.iaf.platform.common.domain.ForbiddenException;

/**
 * What a service account may call. People are judged by roles and teams alone; a service account
 * is in addition held to its scopes, which the scope filter maps from each endpoint. An endpoint
 * no rule names needs {@code admin}, and {@code admin} allows everything (and makes the account an
 * admin). Generated routes and MCP tools are judged one by one: starting a job needs
 * {@code jobs:start}, reading the resource registry needs {@code registry:read}.
 */
public final class Scopes {

    public static final String REGISTRY_READ = "registry:read";
    public static final String WORKFLOWS_VALIDATE = "workflows:validate";
    public static final String WORKFLOWS_DRY_RUN = "workflows:dry-run";
    public static final String WORKFLOWS_WRITE = "workflows:write";
    public static final String JOBS_START = "jobs:start";
    public static final String JOBS_READ = "jobs:read";
    public static final String PROVIDERS_WRITE = "providers:write";
    public static final String ADMIN = "admin";
    /** An endpoint every service account may call; what it does inside is judged there (MCP tools, generated routes). */
    public static final String ANY = "*";

    public static final List<String> ALL = List.of(REGISTRY_READ, WORKFLOWS_VALIDATE, WORKFLOWS_DRY_RUN, WORKFLOWS_WRITE, JOBS_START, JOBS_READ, PROVIDERS_WRITE, ADMIN);

    /** What an account created without scopes gets: read the registry, start and follow jobs. */
    public static final List<String> DEFAULT = List.of(REGISTRY_READ, JOBS_START, JOBS_READ);

    /** Named scope sets; {@code assistant} is the builder agent's token: read, validate and dry-run, nothing that changes anything. */
    public static final Map<String, List<String>> PRESETS = Map.of(
            "assistant", List.of(REGISTRY_READ, WORKFLOWS_VALIDATE, WORKFLOWS_DRY_RUN),
            "invoker", DEFAULT,
            "ci", List.of(REGISTRY_READ, WORKFLOWS_VALIDATE, WORKFLOWS_DRY_RUN, WORKFLOWS_WRITE, PROVIDERS_WRITE));

    private record Rule(String method, Pattern path, String scope) {
        boolean matches(String m, String rest) {
            return ("*".equals(method) || method.equalsIgnoreCase(m)) && path.matcher(rest).matches();
        }
    }

    private static final String ID = "[^/:]+";

    /** The platform's own endpoints, by the path after {@code /api/v<n>}. */
    private static final List<Rule> RULES = List.of(
            rule("GET", "/me", ANY),
            rule("*", "/mcp", ANY),
            // the registry and the catalog
            rule("GET", "/registry(/.*)?", REGISTRY_READ),
            rule("GET", "/catalog", REGISTRY_READ),
            rule("GET", "/openapi(\\.json)?", REGISTRY_READ),
            rule("GET", "/templates", REGISTRY_READ),
            rule("GET", "/workflows(/" + ID + "(/versions(/" + ID + ")?)?)?", REGISTRY_READ),
            rule("GET", "/providers(/" + ID + "(/alerts)?)?", REGISTRY_READ),
            rule("GET", "/resource-types(/" + ID + ")?", REGISTRY_READ),
            rule("GET", "/resources(/" + ID + "(/lineage)?)?", REGISTRY_READ),
            rule("GET", "/change-requests(/" + ID + ")?", REGISTRY_READ),
            rule("GET", "/releases(/" + ID + ")?", REGISTRY_READ),
            // checks that change nothing
            rule("POST", "/workflows:(validate|parse|render)", WORKFLOWS_VALIDATE),
            rule("POST", "/workflows/" + ID + "/versions/" + ID + ":replay-check", WORKFLOWS_VALIDATE),
            rule("POST", "/providers:validate", WORKFLOWS_VALIDATE),
            rule("POST", "/workflows:dry-run", WORKFLOWS_DRY_RUN),
            // authoring
            rule("POST", "/workflows", WORKFLOWS_WRITE),
            rule("POST", "/workflows:import", WORKFLOWS_WRITE),
            rule("PUT", "/workflows/" + ID + "/draft", WORKFLOWS_WRITE),
            rule("DELETE", "/workflows/" + ID, WORKFLOWS_WRITE),
            rule("POST", "/workflows/" + ID + ":publish", WORKFLOWS_WRITE),
            rule("POST", "/change-requests/" + ID + ":(merge|close)", WORKFLOWS_WRITE),
            rule("*", "/assistant/sessions(/.*)?", WORKFLOWS_WRITE),
            rule("POST", "/assistant/drafts", WORKFLOWS_WRITE),
            // jobs
            rule("POST", "/jobs", JOBS_START),
            rule("POST", "/jobs:batch", JOBS_START),
            rule("POST", "/jobs/" + ID + ":(cancel|retry|skip|abort|rerun|retry-failed-rows)", JOBS_START),
            rule("POST", "/triggers/(alerts|events)", JOBS_START),
            rule("GET", "/jobs(/" + ID + "(/(events|replay|evidence))?)?", JOBS_READ),
            rule("GET", "/approvals(/" + ID + ")?", JOBS_READ),
            rule("GET", "/audit/" + ID + "(/export|:verify)?", JOBS_READ),
            // providers
            rule("POST", "/providers:(import-spec|test-connection|try|publish|draft|import-ansible|import-terraform)", PROVIDERS_WRITE),
            rule("POST", "/providers/" + ID + "/overrides", PROVIDERS_WRITE),
            rule("PUT", "/providers/" + ID + "/slo", PROVIDERS_WRITE),
            rule("POST", "/providers/" + ID + ":refresh-spec", PROVIDERS_WRITE),
            // the list holds names, types and owners, never a value: a read like GET /registry/credentials
            rule("GET", "/credentials", REGISTRY_READ),
            rule("*", "/credentials", PROVIDERS_WRITE));

    private static final Pattern VERSIONED = Pattern.compile("^/api/v\\d+(/.*)?$");

    private Scopes() {
    }

    private static Rule rule(String method, String path, String scope) {
        return new Rule(method, Pattern.compile(path), scope);
    }

    /**
     * The scope a call needs: one of {@link #ALL}, {@link #ANY}, or {@link #ADMIN} for an endpoint no
     * rule names. {@code generated} says the path is a generated route (the gateway judges those).
     */
    public static String required(String method, String path, boolean generated) {
        String p = path == null ? "" : path;
        var versioned = VERSIONED.matcher(p);
        if (versioned.matches()) {
            String rest = versioned.group(1) == null ? "" : versioned.group(1);
            for (Rule r : RULES) {
                if (r.matches(method, rest)) {
                    return r.scope();
                }
            }
            return generated ? ANY : ADMIN;
        }
        if (p.equals("/openapi.json")) {
            return "GET".equalsIgnoreCase(method) ? REGISTRY_READ : ADMIN;
        }
        if (p.startsWith("/ws/jobs/")) {
            return JOBS_READ;
        }
        if (p.startsWith("/actuator/") && "GET".equalsIgnoreCase(method)) {
            return ANY;
        }
        return ADMIN;
    }

    public static boolean allows(List<String> granted, String scope) {
        return ANY.equals(scope) || (granted != null && (granted.contains(ADMIN) || granted.contains(scope)));
    }

    /** Throws 403 unless {@code granted} allows {@code scope}; {@code granted} null means a person, who has no scope limits. */
    public static void check(List<String> granted, String scope, String what) {
        if (granted != null && !allows(granted, scope)) {
            throw new ForbiddenException("This service account may not " + what + " (needs scope " + scope + ", has " + String.join(", ", granted) + ")");
        }
    }

    /** The scopes asked for, deduplicated and checked; the preset's when one is named; {@link #DEFAULT} when neither is given. */
    public static List<String> resolve(List<String> requested, String preset) {
        List<String> fromPreset = null;
        if (preset != null && !preset.isBlank()) {
            fromPreset = PRESETS.get(preset.trim());
            if (fromPreset == null) {
                throw new IllegalArgumentException("unknown scope preset '" + preset + "' (one of " + String.join(", ", PRESETS.keySet().stream().sorted().toList()) + ")");
            }
        }
        if (requested == null || requested.isEmpty()) {
            return fromPreset != null ? fromPreset : DEFAULT;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String s : requested) {
            String t = s == null ? "" : s.trim();
            if (ALL.contains(t)) {
                out.add(t);
            } else {
                unknown.add(t);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown scope" + (unknown.size() == 1 ? " " : "s ") + unknown + " (one of " + String.join(", ", ALL) + ")");
        }
        if (fromPreset != null && !fromPreset.containsAll(out)) {
            throw new IllegalArgumentException("the " + preset.trim() + " preset allows only " + String.join(", ", fromPreset));
        }
        return List.copyOf(out);
    }
}

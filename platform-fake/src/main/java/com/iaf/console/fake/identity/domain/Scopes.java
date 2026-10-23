package com.iaf.console.fake.identity.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The scopes of the platform and its presets (P-03 adds the console ones). */
public final class Scopes {

    public static final String REGISTRY_READ = "registry:read";
    public static final String WORKFLOWS_VALIDATE = "workflows:validate";
    public static final String WORKFLOWS_DRY_RUN = "workflows:dry-run";
    public static final String WORKFLOWS_WRITE = "workflows:write";
    public static final String JOBS_START = "jobs:start";
    public static final String JOBS_READ = "jobs:read";
    public static final String PROVIDERS_WRITE = "providers:write";
    public static final String ADMIN = "admin";
    public static final String CONSOLE_ACT = "console:act";
    public static final String RESOURCES_OBSERVE = "resources:observe";
    public static final String EVENTS_READ = "events:read";
    /** P-05's backfill, for the console-internal preset, so the state service imports the registry without a person. */
    public static final String RESOURCES_BACKFILL = "resources:backfill";

    public static final Set<String> ALL = Set.of(REGISTRY_READ, WORKFLOWS_VALIDATE, WORKFLOWS_DRY_RUN, WORKFLOWS_WRITE, JOBS_START, JOBS_READ,
            PROVIDERS_WRITE, ADMIN, CONSOLE_ACT, RESOURCES_OBSERVE, EVENTS_READ, RESOURCES_BACKFILL);

    /** The named scope sets. */
    public static final Map<String, List<String>> PRESETS = Map.of(
            "invoker", List.of(REGISTRY_READ, JOBS_START, JOBS_READ),
            "assistant", List.of(REGISTRY_READ, WORKFLOWS_VALIDATE, WORKFLOWS_DRY_RUN, JOBS_READ),
            "ci", List.of(REGISTRY_READ, WORKFLOWS_VALIDATE, WORKFLOWS_WRITE, JOBS_START, JOBS_READ),
            "console", List.of(REGISTRY_READ, JOBS_START, JOBS_READ, RESOURCES_OBSERVE, CONSOLE_ACT),
            "console-internal", List.of(REGISTRY_READ, JOBS_READ, RESOURCES_OBSERVE, EVENTS_READ, RESOURCES_BACKFILL));

    /** What a console account can never hold. */
    public static final Set<String> FORBIDDEN_FOR_CONSOLE = Set.of(ADMIN, WORKFLOWS_WRITE, PROVIDERS_WRITE);

    private Scopes() {
    }

    public static boolean isKnown(String scope) {
        return ALL.contains(scope);
    }
}

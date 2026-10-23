package com.iaf.console.platform.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonValue;

/** A scope of a service account (P-03 adds the three console scopes). */
public enum Scope {

    REGISTRY_READ("registry:read"),
    WORKFLOWS_VALIDATE("workflows:validate"),
    WORKFLOWS_DRY_RUN("workflows:dry-run"),
    WORKFLOWS_WRITE("workflows:write"),
    JOBS_START("jobs:start"),
    JOBS_READ("jobs:read"),
    PROVIDERS_WRITE("providers:write"),
    ADMIN("admin"),
    CONSOLE_ACT("console:act"),
    RESOURCES_OBSERVE("resources:observe"),
    EVENTS_READ("events:read"),
    /** A scope this client does not know yet; the platform may add some. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown");

    private final String id;

    Scope(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public static Scope of(String id) {
        for (Scope s : values()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return UNKNOWN;
    }
}

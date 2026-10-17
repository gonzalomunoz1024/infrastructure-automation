package com.iaf.platform.common.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What an action does to the world. Drives approval defaults, previews and retry safety. */
public enum Effect {
    READ, CREATE, CHANGE, DELETE, NONE, UNKNOWN;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static Effect of(String s) {
        if (s == null) {
            return UNKNOWN;
        }
        return switch (s.toLowerCase()) {
            case "read", "get", "list" -> READ;
            case "create", "post" -> CREATE;
            case "change", "update", "put", "patch" -> CHANGE;
            case "delete", "destroy" -> DELETE;
            case "none" -> NONE;
            default -> UNKNOWN;
        };
    }

    /** The default effect of an HTTP method, used when a spec or a user says nothing. */
    public static Effect ofHttpMethod(String method) {
        return switch (method == null ? "" : method.toUpperCase()) {
            case "GET", "HEAD", "OPTIONS" -> READ;
            case "POST" -> CREATE;
            case "PUT", "PATCH" -> CHANGE;
            case "DELETE" -> DELETE;
            default -> UNKNOWN;
        };
    }

    public boolean destructive() {
        return this == DELETE;
    }
}

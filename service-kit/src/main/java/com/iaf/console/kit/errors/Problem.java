package com.iaf.console.kit.errors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * One finding about an input, in the platform's shape, so a problem the console shows is the same
 * problem the platform would report: a level, a stable code, where it is and what to do.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(Level level, String code, String step, String field, String message, Fix fix) {

    /** How serious the finding is. */
    public enum Level {
        ERROR, WARNING;

        @JsonValue
        public String json() {
            return name().toLowerCase();
        }
    }

    /** A one-click fix a client can apply. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fix(String label, String kind, String value) {
    }

    public static Problem error(String code, String field, String message) {
        return new Problem(Level.ERROR, code, null, field, message, null);
    }

    public static Problem error(String code, String step, String field, String message) {
        return new Problem(Level.ERROR, code, step, field, message, null);
    }

    public static Problem warning(String code, String field, String message) {
        return new Problem(Level.WARNING, code, null, field, message, null);
    }

    @JsonIgnore
    public boolean isError() {
        return level == Level.ERROR;
    }
}

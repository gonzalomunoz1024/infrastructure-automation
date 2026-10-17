package com.iaf.platform.common.domain;

/**
 * One finding of a check: the editor, the validate endpoint and the change-request checks all
 * speak this shape, so a problem shown on the canvas is the same problem CI would report.
 */
public record Problem(Level level, String code, String step, String field, String message, Fix fix) {

    public enum Level {
        ERROR, WARNING;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() {
            return name().toLowerCase();
        }
    }

    /** A one-click fix the editor can apply. */
    public record Fix(String label, String kind, String value) {
        public static Fix replace(String label, String value) {
            return new Fix(label, "replace", value);
        }

        public static Fix insertApproval(String label) {
            return new Fix(label, "insert-approval", null);
        }

        public static Fix connect(String label) {
            return new Fix(label, "connect", null);
        }
    }

    public static Problem error(String code, String step, String field, String message) {
        return new Problem(Level.ERROR, code, step, field, message, null);
    }

    public static Problem error(String code, String step, String field, String message, Fix fix) {
        return new Problem(Level.ERROR, code, step, field, message, fix);
    }

    public static Problem warning(String code, String step, String field, String message) {
        return new Problem(Level.WARNING, code, step, field, message, null);
    }

    public static Problem warning(String code, String step, String field, String message, Fix fix) {
        return new Problem(Level.WARNING, code, step, field, message, fix);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isError() {
        return level == Level.ERROR;
    }
}

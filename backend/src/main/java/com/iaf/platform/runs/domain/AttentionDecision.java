package com.iaf.platform.runs.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

/** What a person decided on a job that needs attention. {@code input} is only set for edit-input. */
public record AttentionDecision(Action action, JsonNode input, String by) {

    public enum Action {
        RETRY("retry"), EDIT_INPUT("edit-input"), SKIP("skip"), ABORT("abort");

        private final String key;

        Action(String key) {
            this.key = key;
        }

        @JsonValue
        public String json() {
            return key;
        }

        @JsonCreator
        public static Action of(String key) {
            for (Action a : values()) {
                if (a.key.equals(key) || a.name().equalsIgnoreCase(key)) {
                    return a;
                }
            }
            throw new IllegalArgumentException("Unknown attention action '" + key + "'");
        }
    }

    public static AttentionDecision retry(String by) {
        return new AttentionDecision(Action.RETRY, null, by);
    }

    public static AttentionDecision editInput(JsonNode input, String by) {
        return new AttentionDecision(Action.EDIT_INPUT, input, by);
    }

    public static AttentionDecision skip(String by) {
        return new AttentionDecision(Action.SKIP, null, by);
    }

    public static AttentionDecision abort(String by) {
        return new AttentionDecision(Action.ABORT, null, by);
    }
}

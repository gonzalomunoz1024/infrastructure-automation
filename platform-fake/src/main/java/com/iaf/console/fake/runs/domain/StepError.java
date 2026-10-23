package com.iaf.console.fake.runs.domain;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Why a step failed: a class from the provider's errors table or the platform's, a message and whether a retry may help. */
public record StepError(String errorClass, String message, boolean retryable) {

    public ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("class", errorClass);
        out.put("message", message);
        out.put("retryable", retryable);
        return out;
    }
}

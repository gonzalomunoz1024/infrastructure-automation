package com.iaf.console.state.stack.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A step the BFF starts now: create (mint the child naming the stack step, then start its create
 * job) or delete (start the type's delete workflow on {@code wfrn}, then record the delete), with
 * the resolved input and the Idempotency-Key it uses, the same for the same attempt.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReadyStep(String step, StepAction action, int attempt, String idempotencyKey, String type, String service, ObjectNode input,
        String wfrn, String reason) {

    /** {@code stack-<ulid>:<step>:<action>:<attempt>}: per stack, step, action and attempt. */
    public static String keyOf(String stackWfrn, String step, StepAction action, int attempt) {
        String id = stackWfrn.substring(stackWfrn.lastIndexOf('/') + 1);
        return id + ":" + step + ":" + action.wire() + ":" + attempt;
    }
}

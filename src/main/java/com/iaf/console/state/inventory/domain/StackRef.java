package com.iaf.console.state.inventory.domain;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The stack step a resource was created for (hub decision 33): the stack's wfrn and the resource
 * id of the blueprint. On a mint it also carries the attempt of the ready step it answers; the
 * resource keeps the stack and the step only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StackRef(String wfrn, String step, Integer attempt) {

    public StackRef {
        Objects.requireNonNull(wfrn, "wfrn");
        Objects.requireNonNull(step, "step");
    }

    /** The reference a resource keeps: without the attempt. */
    public StackRef kept() {
        return attempt == null ? this : new StackRef(wfrn, step, null);
    }
}

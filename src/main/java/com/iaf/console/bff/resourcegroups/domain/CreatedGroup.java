package com.iaf.console.bff.resourcegroups.domain;

/** A group with the Idempotency-Key it was created with, as the store finds it by that key. */
public record CreatedGroup(ResourceGroup group, CreateKey key) {
}

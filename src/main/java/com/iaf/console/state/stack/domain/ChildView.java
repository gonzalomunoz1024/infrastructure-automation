package com.iaf.console.state.stack.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.state.inventory.domain.ResourceState;

/** What a stack sees of a child resource: its state and version, and what references read of it. */
public record ChildView(String wfrn, ResourceState state, long version, String registryId, String name, ObjectNode attributes, String error) {
}

package com.iaf.console.state.stack.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A resource of a blueprint, one step of its stacks: the type, the create workflow's input, the
 * tags, what teardown does with it and every resource it waits for ({@code dependsOn}, its own and
 * the ones its references name).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlueprintResource(
        @JsonIgnore String id,
        String type,
        String title,
        ObjectNode input,
        Map<String, String> tags,
        List<String> dependsOn,
        DeletionPolicy deletionPolicy,
        ObjectNode deleteInput) {

    public BlueprintResource {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        deletionPolicy = deletionPolicy == null ? DeletionPolicy.DELETE : deletionPolicy;
    }
}

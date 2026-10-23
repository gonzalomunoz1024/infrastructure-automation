package com.iaf.console.bff.resources.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A pointer to a resource on another page: lineage nodes, affected resources of a preview. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceRef(String wfrn, String type, String name, String state, String id) {

    public static ResourceRef of(StateResource resource) {
        return new ResourceRef(resource.wfrn(), resource.type(), resource.name(), resource.state(), resource.registryId());
    }
}

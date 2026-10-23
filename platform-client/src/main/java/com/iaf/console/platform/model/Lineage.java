package com.iaf.console.platform.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A resource with what it was built from and what was built from it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Lineage(Resource resource, List<Resource> builtFrom, List<Resource> dependents, List<Resource> nodes, List<Edge> edges) {

    /** One edge of the lineage graph. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edge(String from, String to, String via) {
    }
}

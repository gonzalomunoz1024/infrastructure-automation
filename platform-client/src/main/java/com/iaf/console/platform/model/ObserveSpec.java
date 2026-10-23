package com.iaf.console.platform.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** How a resource type's truth is read from its provider: the read action, its input, the field mapping and the paged reads. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ObserveSpec(
        String provider,
        String action,
        Map<String, Object> input,
        Map<String, String> map,
        List<String> ignore,
        String interval,
        String notFound,
        ObserveListSpec discover,
        ObserveListSpec list,
        MetricsSpec metrics) {

    /** The metrics read action of a type, when it has one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MetricsSpec(String action, Map<String, Object> input, Map<String, String> map) {
    }
}

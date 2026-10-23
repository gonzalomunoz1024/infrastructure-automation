package com.iaf.console.platform.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A kind: Region (P-07): its partition, status, environments and whether it is the default. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Region(
        String name,
        String title,
        String description,
        String partition,
        String status,
        List<String> environments,
        @JsonProperty("default") Boolean isDefault) {
}

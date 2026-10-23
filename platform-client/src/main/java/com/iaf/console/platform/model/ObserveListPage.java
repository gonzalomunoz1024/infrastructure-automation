package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One page of observed items (P-09): each with its provider id, whether the registry manages it, and its wfrn when it does. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ObserveListPage(List<Item> items, String next, String provider, String action, Instant at) {

    /** One observed item of a paged read. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String providerId, String wfrn, String id, Map<String, Object> observed, Boolean managed) {
    }
}

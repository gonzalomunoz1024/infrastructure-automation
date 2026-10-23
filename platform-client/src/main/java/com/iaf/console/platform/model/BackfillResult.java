package com.iaf.console.platform.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** How many wfrns a backfill minted and skipped, and which. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BackfillResult(int minted, int skipped, List<Item> items) {

    /** One minted wfrn. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String id, String wfrn) {
    }
}

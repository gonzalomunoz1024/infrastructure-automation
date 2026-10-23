package com.iaf.console.search.index.domain;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A resource as the state service serves it ({@code ConsoleResource} of its contract) or its
 * tombstone ({@code Tombstone}, the 410 body), untouched: the fold reads what it needs.
 */
public record StateResource(JsonNode json, boolean tombstone) {

    public StateResource {
        Objects.requireNonNull(json, "json");
    }

    public static StateResource of(JsonNode json) {
        return new StateResource(json, false);
    }

    public static StateResource tombstone(JsonNode json) {
        return new StateResource(json, true);
    }

    public String wfrn() {
        return Json.text(json, "wfrn");
    }

    public String type() {
        return Json.text(json, "type");
    }

    public long version() {
        Long v = Json.longValue(json, tombstone ? "lastVersion" : "version");
        return v == null ? 0 : v;
    }
}

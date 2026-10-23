package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One hashed entry of an evidence record. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvidenceEntry(long seq, Instant at, String kind, String actor, Map<String, Object> data, String hash, String previous) {
}

package com.iaf.console.bff.resources.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** What a deleted resource's name answers for 90 days: the state service's 410 body. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tombstone(String error, String wfrn, Instant deletedAt, Instant tombstoneUntil, Long lastVersion) {
}

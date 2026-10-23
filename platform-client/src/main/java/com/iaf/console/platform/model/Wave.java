package com.iaf.console.platform.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One wave of a batch rollout. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Wave(int index, int percent, int size, String status, Instant startedAt, Instant endedAt) {
}

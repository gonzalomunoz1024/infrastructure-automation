package com.iaf.console.platform.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** The rows and waves of a batch job. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Batch(List<BatchRow> rows, List<Wave> waves, RolloutPolicy rollout, String stoppedReason) {
}

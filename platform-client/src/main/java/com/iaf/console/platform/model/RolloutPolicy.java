package com.iaf.console.platform.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** How a batch is released: wave sizes, parallelism and the failure percentage that stops it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RolloutPolicy(List<Integer> waves, Integer maxParallel, Integer stopIfFailedPercent) {
}

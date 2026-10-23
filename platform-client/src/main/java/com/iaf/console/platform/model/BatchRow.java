package com.iaf.console.platform.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One row of a batch and the child job it became. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchRow(int index, Map<String, Object> input, String jobId, String status, String error, Integer wave) {
}

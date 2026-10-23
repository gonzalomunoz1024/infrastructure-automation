package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Why a job needs attention: the step, its error and what the operator may do. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Attention(String step, StepError error, List<String> options, Instant since, String timeout) {
}

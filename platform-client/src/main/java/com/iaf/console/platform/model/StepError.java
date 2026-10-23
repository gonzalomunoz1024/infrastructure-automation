package com.iaf.console.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Why a step, an observe call or a provider call failed, and whether a retry may help. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record StepError(@JsonProperty("class") String errorClass, String message, Boolean retryable) {
}

package com.iaf.console.state.inventory.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Why the last platform job on a resource failed, kept on the resource until the next job succeeds. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LastError(String jobId, String status, String errorClass, String failedStep, String message) {
}

package com.iaf.console.bff.resources.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A command the platform took: the resource, the job and the console request id, and whether the job waits for an approval. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandAccepted(String wfrn, String jobId, String requestId, Boolean approval) {
}

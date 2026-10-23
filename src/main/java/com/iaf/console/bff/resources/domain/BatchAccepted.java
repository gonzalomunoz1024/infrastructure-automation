package com.iaf.console.bff.resources.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A batch the platform took: the parent job, its row count, the console request id and, for a create, the minted names in row order. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchAccepted(String jobId, Integer rows, String requestId, List<String> wfrns) {
}

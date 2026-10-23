package com.iaf.console.platform.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** The console context of a job (P-04): account, region, the resource's wfrn, the console request and labels. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobContext(String accountId, String region, String wfrn, String consoleRequestId, Map<String, String> labels) {
}

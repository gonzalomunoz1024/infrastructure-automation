package com.iaf.console.bff.drift.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One scan of a type's provider for what the console does not manage, which the Unmanaged page
 * polls until it ends: how many objects the provider has ({@code seen}) and how many of them no
 * account manages ({@code unmanaged}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscoveryRun(String runId, String type, String region, String status, Instant startedAt, Instant endedAt, Integer seen,
        Integer unmanaged, String error) {

    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
}

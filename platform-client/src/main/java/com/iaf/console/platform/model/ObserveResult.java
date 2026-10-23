package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A resource's truth as its provider reports it, mapped through the type's observe block (P-09). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ObserveResult(
        String status,
        Map<String, Object> observed,
        StepError error,
        String provider,
        String action,
        String providerVersion,
        String region,
        String environment,
        Instant at,
        Long durationMs,
        String id,
        String wfrn) {

    public boolean found() {
        return "found".equals(status);
    }
}

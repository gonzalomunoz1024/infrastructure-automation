package com.iaf.console.platform.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A job-start bucket (P-10): its limit, burst, what is left and when it refills. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Quota(String key, int limitPerMinute, int burst, int available, Instant refillsAt, String source) {
}

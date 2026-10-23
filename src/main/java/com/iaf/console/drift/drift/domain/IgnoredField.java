package com.iaf.console.drift.drift.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A field an account chose to ignore: silent while the desired attributes stay what they were
 * when the decision was taken (their digest), and until {@code until} when the decision named one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IgnoredField(String path, String desiredDigest, Instant until, String by, Instant since) {

    public boolean active(String currentDesiredDigest, Instant now) {
        if (desiredDigest != null && !desiredDigest.equals(currentDesiredDigest)) {
            return false;
        }
        return until == null || now.isBefore(until);
    }
}

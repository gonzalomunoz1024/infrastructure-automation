package com.iaf.console.bff.jobs.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.iaf.console.bff.access.domain.Denial;

/**
 * Why a job failed and the fix to try (WP 9.2, hub decision 27, schema {@code JobExplanation}).
 * {@code fix.input} is a merge over the job's input that the console sends, with
 * {@code fix.fromStep}, as the body of the phase 4 retry.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobExplanation(String jobId, String status, Cause cause, FailedStep failedStep, Fix fix, Retry retry, Similar similar, String source,
        List<Link> links) {

    public static final String RULES = "rules";
    public static final String ASSISTANT = "assistant";
    public static final String FAILED = "failed";
    public static final String NEEDS_ATTENTION = "needs-attention";

    public JobExplanation {
        links = links == null || links.isEmpty() ? null : List.copyOf(links);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cause(@JsonProperty("class") String errorClass, String title, String detail) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FailedStep(String id, String name, Integer attempt, String message) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fix(String summary, Map<String, Object> input, String fromStep, String confidence) {

        public Fix {
            input = input == null || input.isEmpty() ? null : Map.copyOf(input);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Retry(boolean allowed, Denial denied) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Similar(long count, LastFixed lastFixed) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LastFixed(String jobId, String by, Instant at) {
    }

    public record Link(String title, String url) {
    }
}

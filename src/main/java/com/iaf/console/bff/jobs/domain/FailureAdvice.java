package com.iaf.console.bff.jobs.domain;

import java.util.List;
import java.util.Map;

/**
 * An explanation of a failure: the cause in plain words, the fix to try with its confidence
 * (high, medium or low), an input to merge over the job's when the fix changes it, and links.
 */
public record FailureAdvice(String title, String detail, String fixSummary, Map<String, Object> input, String confidence, List<JobExplanation.Link> links) {

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";

    public FailureAdvice {
        input = input == null ? Map.of() : Map.copyOf(input);
        links = links == null ? List.of() : List.copyOf(links);
        if (!HIGH.equals(confidence) && !MEDIUM.equals(confidence) && !LOW.equals(confidence)) {
            throw new IllegalArgumentException("a fix's confidence is high, medium or low, was " + confidence);
        }
    }
}

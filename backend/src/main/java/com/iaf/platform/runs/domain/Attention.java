package com.iaf.platform.runs.domain;

import java.time.Instant;
import java.util.List;

import com.iaf.platform.runs.domain.JobStep.StepError;

/**
 * A job paused on a failed step, waiting for a person to decide: retry, retry with an edited
 * input, skip the step, or abort (and compensate). {@code timeout} is how long the job waits
 * before it aborts on its own.
 */
public record Attention(String step, StepError error, List<String> options, Instant since, String timeout) {

    public static final List<String> OPTIONS = List.of("retry", "edit-input", "skip", "abort");

    /** How long a job waits for a decision before it gives up and aborts. */
    public static final String DEFAULT_TIMEOUT = "P7D";
}

package com.iaf.platform.runs.domain;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** A walk of the flow against recorded samples: the path taken and each step's input and output. */
public record DryRunResult(boolean passed, List<String> path, List<DryRunStep> steps, String error) {

    public record DryRunStep(String id, String name, String status, JsonNode input, JsonNode output, String error) {
    }
}

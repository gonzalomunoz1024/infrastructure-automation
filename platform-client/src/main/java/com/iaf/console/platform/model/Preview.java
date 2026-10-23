package com.iaf.console.platform.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** What a run would do: its steps, affected resources, cost and policy decision. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Preview(
        String workflow,
        String version,
        List<PreviewStep> steps,
        List<AffectedResource> affectedResources,
        Cost cost,
        PolicyDecision policy) {
}

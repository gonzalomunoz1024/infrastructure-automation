package com.iaf.console.state.inventory.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The open finding on a resource, the contract's {@code Drift}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Drift(String findingId, Instant since, String severity, List<DriftField> fields) {

    public Drift {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}

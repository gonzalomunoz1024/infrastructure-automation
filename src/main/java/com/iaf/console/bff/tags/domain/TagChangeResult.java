package com.iaf.console.bff.tags.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.iaf.console.bff.access.domain.Denial;

/**
 * What a bulk tag change did: the resources it changed, with their tags as they now stand, and
 * those it could not, each with why ({@code denied} with the Denial, {@code not-found},
 * {@code gone} for a deleted one, {@code conflict}, or the state service's error).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TagChangeResult(List<Applied> applied, List<Failed> failed) {

    public TagChangeResult {
        applied = applied == null ? List.of() : List.copyOf(applied);
        failed = failed == null ? List.of() : List.copyOf(failed);
    }

    public record Applied(String wfrn, Map<String, String> tags) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Failed(String wfrn, String error, String message, Denial denied) {
    }
}

package com.iaf.console.bff.assistant.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What an {@code explain} turn answers, the contract's {@code ResourceExplanation}: a summary of
 * what the resource is and how it is doing, sections in plain words, and the facts the BFF
 * gathered itself (never what the assistant claims).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceExplanation(String wfrn, String summary, List<Section> sections, Facts facts) {

    public ResourceExplanation {
        sections = sections == null || sections.isEmpty() ? null : List.copyOf(sections);
    }

    /** One part of the explanation: configuration, history, dependents, drift. */
    public record Section(String title, String text) {
    }

    /**
     * The resource's facts: cost (left out until a cost source answers), who created it and when,
     * its last change, how many resources depend on it and whether it drifted.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Facts(Double monthlyCost, String currency, Instant createdAt, String createdBy, Instant lastChangeAt, String lastChangeBy,
            Long dependents, Boolean drift) {
    }
}

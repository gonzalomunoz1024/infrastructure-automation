package com.iaf.console.bff.digests.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An account's weekly digest (decision 32.4): the week (Monday to Sunday, UTC), when it was
 * written and by what ({@code assistant} or {@code rules}), the headline, and the sections
 * {@code changes}, {@code drift}, {@code cost} and {@code access}, each with its text, its facts
 * and the highlights that link on.
 */
public record Digest(String accountId, LocalDate weekStart, LocalDate weekEnd, Instant generatedAt, String source, String headline, List<Section> sections) {

    public static final String ASSISTANT = "assistant";
    public static final String RULES = "rules";

    public static final String CHANGES = "changes";
    public static final String DRIFT = "drift";
    public static final String COST = "cost";
    public static final String ACCESS = "access";
    public static final List<String> KINDS = List.of(CHANGES, DRIFT, COST, ACCESS);

    public Digest {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(weekStart, "weekStart");
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /** One section: its kind and title, the text written from its facts, the facts, and highlights with a console path where one opens it. */
    public record Section(String kind, String title, String text, Map<String, Object> facts, List<Highlight> highlights) {
        public Section {
            facts = facts == null ? Map.of() : facts;
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Highlight(String text, String link) {
    }

    public DigestSummary summary() {
        return new DigestSummary(weekStart, headline, generatedAt);
    }

    /** A section's title. */
    public static String titleOf(String kind) {
        return switch (kind) {
            case CHANGES -> "Changes";
            case DRIFT -> "Drift";
            case COST -> "Cost";
            case ACCESS -> "Access";
            default -> kind;
        };
    }
}

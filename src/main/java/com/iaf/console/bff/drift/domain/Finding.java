package com.iaf.console.bff.drift.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A drift finding as the drift service answers it, plus what the person may do with it
 * ({@code can}), so the findings page and a resource's Drift tab draw from the one shape the
 * contract names. A closed finding can do nothing more, so its {@code can} is empty.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Finding(
        String findingId,
        String wfrn,
        String accountId,
        String region,
        String type,
        String status,
        String severity,
        Long version,
        List<Field> fields,
        Instant openedAt,
        Instant observedAt,
        Instant closedAt,
        String resolution,
        String decidedBy,
        String reason,
        String revertJobId,
        String revertStatus,
        Instant ignoredUntil,
        List<String> can) {

    public static final String OPEN = "open";
    public static final String REVERT = "revert";
    public static final String ACCEPT = "accept";
    public static final String IGNORE = "ignore";
    /** What {@code <service>:update} on the finding's wfrn lets a person do with an open finding. */
    public static final List<String> DECISIONS = List.of(REVERT, ACCEPT, IGNORE);

    public Finding {
        fields = fields == null ? List.of() : List.copyOf(fields);
        can = can == null ? null : List.copyOf(can);
    }

    /**
     * One drifted field: where it is in the attributes (a JSON pointer), what the console wants,
     * what the provider reports, and whether the account silenced it. A value may be JSON null.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(String path, JsonNode expected, JsonNode observed, Boolean ignored) {
    }

    public boolean open() {
        return OPEN.equals(status);
    }

    /** The finding with what the person may do: the decisions when they may update the resource and it is still open, else none. */
    public Finding decidable(boolean mayUpdate) {
        return withCan(mayUpdate && open() ? DECISIONS : List.of());
    }

    public Finding withCan(List<String> allowed) {
        return new Finding(findingId, wfrn, accountId, region, type, status, severity, version, fields, openedAt, observedAt, closedAt, resolution,
                decidedBy, reason, revertJobId, revertStatus, ignoredUntil, allowed);
    }
}

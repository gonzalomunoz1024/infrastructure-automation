package com.iaf.console.bff.drift.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A rightsizing recommendation: an idle resource to delete or an oversized one to resize, with
 * the utilisation behind it, the monthly cost now and the saving. {@code can} says whether the
 * person may apply it (update for a resize, delete for a delete) and dismiss it (update); a
 * recommendation that is no longer open can do nothing more.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Recommendation(
        String recommendationId,
        String wfrn,
        String accountId,
        String region,
        String type,
        String name,
        String kind,
        String action,
        String status,
        JsonNode current,
        JsonNode proposed,
        Utilisation utilisation,
        Money monthlyCost,
        Money monthlySaving,
        String jobId,
        String decidedBy,
        String reason,
        Instant dismissedUntil,
        Instant createdAt,
        Instant updatedAt,
        List<String> can) {

    public static final String OPEN = "open";
    public static final String DELETE = "delete";
    public static final String RESIZE = "resize";
    public static final String APPLY = "apply";
    public static final String DISMISS = "dismiss";

    public Recommendation {
        can = can == null ? null : List.copyOf(can);
    }

    /** The CPU and memory a recommendation was judged on: 95th percentiles and peaks, in percent, over a window. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Utilisation(Instant from, Instant to, Integer samples, Double cpuP95, Double cpuMax, Double memoryP95, Double memoryMax) {
    }

    public boolean open() {
        return OPEN.equals(status);
    }

    public boolean deletes() {
        return DELETE.equals(action);
    }

    /** The verb applying it needs on the resource: delete for a delete, update for a resize. */
    public String applyVerb() {
        return deletes() ? "delete" : "update";
    }

    /** The recommendation with what the person may do, as their policies decided on its wfrn; nothing once it is no longer open. */
    public Recommendation decidable(boolean mayApply, boolean mayDismiss) {
        List<String> allowed = new ArrayList<>(2);
        if (open() && mayApply) {
            allowed.add(APPLY);
        }
        if (open() && mayDismiss) {
            allowed.add(DISMISS);
        }
        return withCan(allowed);
    }

    public Recommendation withCan(List<String> allowed) {
        return new Recommendation(recommendationId, wfrn, accountId, region, type, name, kind, action, status, current, proposed, utilisation,
                monthlyCost, monthlySaving, jobId, decidedBy, reason, dismissedUntil, createdAt, updatedAt, allowed);
    }
}

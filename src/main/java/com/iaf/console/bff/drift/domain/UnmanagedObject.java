package com.iaf.console.bff.drift.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Something a provider has that no console account manages, as a discovery run found it: what the
 * provider reports, which account it most likely belongs to and why, what it costs a month, and
 * the team it was handed to, if any. {@code can} says whether the person may import or assign it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnmanagedObject(
        String providerId,
        String type,
        String region,
        String name,
        JsonNode observed,
        Instant seenAt,
        Instant firstSeenAt,
        OwnerGuess ownerGuess,
        Money monthlyCost,
        Assignment assignment,
        List<String> can) {

    public static final String IMPORT = "import";
    public static final String ASSIGN = "assign";
    /** What {@code <service>:create} on the type's pattern lets a person do with an unmanaged object. */
    public static final List<String> ACTIONS = List.of(IMPORT, ASSIGN);

    public UnmanagedObject {
        can = can == null ? null : List.copyOf(can);
    }

    /** The account an object most likely belongs to, how sure the guess is, and the reasons in plain words. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OwnerGuess(String accountId, String accountName, String team, String confidence, List<String> reasons) {

        public OwnerGuess {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    /** The object handed to another team's account: it shows in both accounts' lists until either imports it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assignment(String accountId, String accountName, String fromAccountId, String by, String note, Instant at) {
    }

    public UnmanagedObject withCan(List<String> allowed) {
        return new UnmanagedObject(providerId, type, region, name, observed, seenAt, firstSeenAt, ownerGuess, monthlyCost, assignment, allowed);
    }
}

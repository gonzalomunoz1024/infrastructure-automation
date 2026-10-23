package com.iaf.console.drift.drift.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A provider object the console does not manage, found by a discovery run, the contract's
 * {@code UnmanagedObject}: what the provider says of it, who most likely owns it, what it costs a
 * month, and the team it was handed to, if any. The store keeps one copy per account: the copy of
 * the account whose run found it, and the copy of the account it was assigned to.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnmanagedObject(@JsonIgnore String accountId, String providerId, String type, String region, String name, ObjectNode observed,
        Instant seenAt, Instant firstSeenAt, OwnerGuess ownerGuess, Money monthlyCost, Assignment assignment) {

    public UnmanagedObject {
        observed = observed == null ? Json.object() : observed;
    }

    /** An object a run has just found, not yet judged. */
    public static UnmanagedObject found(String accountId, String providerId, String type, String region, String name, ObjectNode observed, Instant at) {
        return new UnmanagedObject(accountId, providerId, type, region, name, observed, at, at, null, null, null);
    }

    /** The same object, under the account the store keeps it for (the account is not part of the JSON). */
    public UnmanagedObject forAccount(String account) {
        return new UnmanagedObject(account, providerId, type, region, name, observed, seenAt, firstSeenAt, ownerGuess, monthlyCost, assignment);
    }

    /** The same object seen again, with what the provider says of it now. */
    public UnmanagedObject seen(ObjectNode now, Instant at) {
        return new UnmanagedObject(accountId, providerId, type, region, Json.text(now, "name") != null ? Json.text(now, "name") : name, now, at,
                firstSeenAt == null ? at : firstSeenAt, ownerGuess, monthlyCost, assignment);
    }

    /** With who most likely owns it and what it costs, either of which may be unknown. */
    public UnmanagedObject judged(OwnerGuess guess, Money cost) {
        return new UnmanagedObject(accountId, providerId, type, region, name, observed, seenAt, firstSeenAt, guess, cost, assignment);
    }

    public UnmanagedObject assigned(Assignment to) {
        return new UnmanagedObject(accountId, providerId, type, region, name, observed, seenAt, firstSeenAt, ownerGuess, monthlyCost, to);
    }

    /** Whether this is the copy another account handed to this one, rather than the copy of the account whose run found it. */
    public boolean handedHere() {
        return assignment != null && accountId != null && accountId.equals(assignment.accountId());
    }

    /** The account whose discovery run found the object. */
    public String foundBy() {
        return handedHere() ? assignment.fromAccountId() : accountId;
    }
}

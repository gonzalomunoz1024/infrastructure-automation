package com.iaf.console.drift.drift.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A provider object the console does not manage, found by a discovery run, the contract's {@code UnmanagedObject}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnmanagedObject(@JsonIgnore String accountId, String providerId, String type, String region, String name, ObjectNode observed,
        Instant seenAt, Instant firstSeenAt) {

    public UnmanagedObject {
        observed = observed == null ? Json.object() : observed;
    }

    /** The same object seen again. */
    /** The same object, under the account the store keeps it for (the account is not part of the JSON). */
    public UnmanagedObject forAccount(String account) {
        return new UnmanagedObject(account, providerId, type, region, name, observed, seenAt, firstSeenAt);
    }

    public UnmanagedObject seen(ObjectNode now, Instant at) {
        return new UnmanagedObject(accountId, providerId, type, region, Json.text(now, "name") != null ? Json.text(now, "name") : name, now, at,
                firstSeenAt == null ? at : firstSeenAt);
    }
}

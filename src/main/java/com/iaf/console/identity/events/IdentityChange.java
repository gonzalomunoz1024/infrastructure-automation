package com.iaf.console.identity.events;

/** One change of an account's identity data, as the listeners of {@link IdentityEvents} see it. */
public record IdentityChange(String type, String aggregate, String aggregateId, String accountId, long version, String action) {
}

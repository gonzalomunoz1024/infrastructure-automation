package com.iaf.console.bff.live.domain;

/**
 * An event with its place in the hub's order and who it is for: an account, {@code *} for every
 * account, or one person ({@code person:<userId>}, a notification), whatever account their
 * session is in.
 */
public record Sequenced(long seq, String accountId, LiveEvent event) {

    public static final String EVERY_ACCOUNT = "*";
    public static final String PERSON_PREFIX = "person:";

    public boolean global() {
        return EVERY_ACCOUNT.equals(accountId);
    }

    /** Whether the event is for one person rather than an account. */
    public boolean personal() {
        return accountId.startsWith(PERSON_PREFIX);
    }

    /** The key a person's own events are published under. */
    public static String personKey(String userId) {
        return PERSON_PREFIX + userId;
    }
}

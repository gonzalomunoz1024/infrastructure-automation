package com.iaf.console.bff.live.domain;

/** An event with its place in the hub's order and the account it is for ({@code *} for every account). */
public record Sequenced(long seq, String accountId, LiveEvent event) {

    public static final String EVERY_ACCOUNT = "*";

    public boolean global() {
        return EVERY_ACCOUNT.equals(accountId);
    }
}

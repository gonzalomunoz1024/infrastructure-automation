package com.iaf.console.bff.insights.domain;

/** What the identity service's account record says of an account: its name, owning team and cost centre (hub decision 25), each when known. */
public record AccountProfile(String name, String owningTeam, String costCentre) {

    public static final AccountProfile UNKNOWN = new AccountProfile(null, null, null);
}

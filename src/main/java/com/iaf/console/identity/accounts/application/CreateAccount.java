package com.iaf.console.identity.accounts.application;

/** The command of {@code POST /v1/accounts}; {@code costCentre} is optional. */
public record CreateAccount(String name, String owningTeam, String ownerEmail, String defaultRegion, String costCentre) {
}

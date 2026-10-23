package com.iaf.console.identity.accounts.application;

import com.iaf.console.identity.accounts.domain.AccountStatus;

/** The command of {@code PATCH /v1/accounts/{accountId}}; a null field stays as it is, and an empty {@code costCentre} clears it. */
public record UpdateAccount(String name, AccountStatus status, String defaultRegion, String costCentre) {
}

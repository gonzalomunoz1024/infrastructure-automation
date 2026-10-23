package com.iaf.console.identity.accounts.application;

import com.iaf.console.identity.accounts.domain.AccountStatus;

/** The command of {@code PATCH /v1/accounts/{accountId}}; a null field stays as it is. */
public record UpdateAccount(String name, AccountStatus status, String defaultRegion) {
}

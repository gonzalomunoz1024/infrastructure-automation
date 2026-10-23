package com.iaf.console.identity.accounts.application;

/** The command of {@code POST /v1/accounts/{accountId}:close}: why the account closes, at most 2000 characters. */
public record CloseAccount(String reason) {
}

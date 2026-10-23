package com.iaf.console.bff.clients.domain;

/** A bearer call naming an account the person is not a member of: 403 {@code account.not-member}. */
public class NotMemberException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "account.not-member";

    private final String accountId;

    public NotMemberException(String accountId) {
        super("you are not a member of the account " + accountId);
        this.accountId = accountId;
    }

    public String accountId() {
        return accountId;
    }
}

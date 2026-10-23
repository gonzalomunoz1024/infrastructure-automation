package com.iaf.console.bff.session.domain;

/** The person signed in at the identity provider but belongs to no console account. */
public final class NoConsoleAccountException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NoConsoleAccountException(String subject) {
        super("the user " + subject + " belongs to no console account");
    }
}

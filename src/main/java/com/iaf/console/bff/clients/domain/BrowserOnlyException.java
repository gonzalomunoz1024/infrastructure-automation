package com.iaf.console.bff.clients.domain;

/**
 * A call a CLI or SDK may not make with its bearer token: signing in, switching the browser
 * session and deciding device grants happen in a signed-in browser only (403 {@code browser.only}).
 */
public class BrowserOnlyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "browser.only";

    public BrowserOnlyException() {
        super("this is done in a browser signed in to the console, not with a CLI or SDK token");
    }
}

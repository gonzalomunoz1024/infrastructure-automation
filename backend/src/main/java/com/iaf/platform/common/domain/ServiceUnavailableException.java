package com.iaf.platform.common.domain;

/** A system the command needs is down for now (503); nothing was changed and the caller may try again. */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

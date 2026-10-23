package com.iaf.console.bff.drift.domain;

/**
 * The drift service's 502: the platform (or the state service) answered it an error. The web app
 * needs the platform's correlation id to chase it, not the BFF's, so the BFF passes the answer on
 * as it came ({@code error}, {@code correlationId}, {@code detail}) instead of wrapping it.
 */
public class DriftGatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String correlationId;
    private final transient Object detail;

    public DriftGatewayException(String error, String correlationId, Object detail) {
        super(error);
        this.correlationId = correlationId;
        this.detail = detail;
    }

    /** The correlation id the drift service answered, the platform's when it had one; null when it gave none. */
    public String correlationId() {
        return correlationId;
    }

    public Object detail() {
        return detail;
    }
}

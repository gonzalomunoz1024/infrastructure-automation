package com.iaf.console.search.index.adapters.opensearch;

/** An answer of OpenSearch that is not the one expected. */
public final class OpenSearchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public OpenSearchException(int status, String message) {
        super("OpenSearch answered " + status + ": " + message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}

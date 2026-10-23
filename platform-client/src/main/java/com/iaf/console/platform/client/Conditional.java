package com.iaf.console.platform.client;

/**
 * A read with an ETag (P-19): the body and its tag, or {@code notModified} when the tag the caller
 * sent is still current, in which case {@code body} is null.
 */
public record Conditional<T>(T body, String etag, boolean notModified) {

    public static <T> Conditional<T> of(T body, String etag) {
        return new Conditional<>(body, etag, false);
    }

    public static <T> Conditional<T> notModified(String etag) {
        return new Conditional<>(null, etag, true);
    }
}

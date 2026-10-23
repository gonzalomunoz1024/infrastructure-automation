package com.iaf.console.platform.client;

/** A 2xx command answer and whether it was the kept result of an earlier request with the same idempotency key. */
public record Accepted<T>(T body, boolean idempotentReplay, String correlationId) {

    public static <T> Accepted<T> of(T body) {
        return new Accepted<>(body, false, null);
    }
}

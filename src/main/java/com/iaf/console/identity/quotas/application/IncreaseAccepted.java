package com.iaf.console.identity.quotas.application;

/** The 202 of a request: its id and {@code open}. */
public record IncreaseAccepted(String requestId, String status) {
}

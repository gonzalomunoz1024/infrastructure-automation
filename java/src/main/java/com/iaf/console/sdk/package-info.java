/**
 * The console BFF's API for Java 21.
 *
 * <p>{@code com.iaf.console.sdk.api} and {@code com.iaf.console.sdk.model} are generated from the
 * contract this branch pins ({@code openapi/console-bff.yaml}). This package is written by hand:
 *
 * <ul>
 *   <li>{@link com.iaf.console.sdk.ConsoleAuth} signs a person in with the device authorization
 *       grant (RFC 8628), refreshes and revokes;
 *   <li>{@link com.iaf.console.sdk.RefreshingCredentials} keep the tokens fresh, one refresh at a
 *       time, and hand each rotated refresh token to the caller;
 *   <li>{@link com.iaf.console.sdk.ConsoleClient} makes the generated API classes send the bearer
 *       token (renewed once after a 401 {@code invalid_token}), an {@code Idempotency-Key} on every
 *       write, and retry a 429 or 503 of an idempotent call after {@code Retry-After}; it pages
 *       through lists and turns refusals into a {@link com.iaf.console.sdk.ConsoleApiException}
 *       carrying the BFF's Denial.
 * </ul>
 */
package com.iaf.console.sdk;

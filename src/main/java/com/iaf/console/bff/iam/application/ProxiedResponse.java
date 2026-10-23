package com.iaf.console.bff.iam.application;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/** What the identity service answered, passed back to the browser. */
public record ProxiedResponse(HttpStatusCode status, MediaType contentType, String etag, byte[] body) {
}

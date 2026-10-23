package com.iaf.console.identity.principals.application;

import java.util.List;

/** The body of {@code POST /v1/principals:resolve}: what the identity provider said about the person. */
public record ResolveRequest(String issuer, String subject, String email, String name, List<String> groups) {
}

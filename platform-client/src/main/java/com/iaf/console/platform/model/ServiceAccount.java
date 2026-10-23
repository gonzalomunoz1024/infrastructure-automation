package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A service account of the platform, bound to a console account when it acts for one. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceAccount(
        String id,
        String name,
        List<String> roles,
        List<String> teams,
        List<Scope> scopes,
        Instant createdAt,
        String consoleAccount) {
}

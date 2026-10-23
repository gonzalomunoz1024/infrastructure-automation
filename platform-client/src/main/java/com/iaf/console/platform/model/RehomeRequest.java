package com.iaf.console.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** P-23: the wfrn a registry record moves from (the state service's {@code transferredFrom}), and the account and wfrn it moves to. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RehomeRequest(String from, String wfrn, String accountId) {
}

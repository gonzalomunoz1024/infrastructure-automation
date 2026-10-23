package com.iaf.console.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** P-23: the account a registry record moves to, and the wfrn the console gave it there. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RehomeRequest(String wfrn, String accountId) {
}

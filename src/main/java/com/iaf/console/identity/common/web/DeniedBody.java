package com.iaf.console.identity.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The 403 body: the contract's error fields plus the denial. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeniedBody(String error, Denial denied, String correlationId) {
}

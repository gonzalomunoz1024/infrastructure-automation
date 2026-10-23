package com.iaf.console.identity.authz.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One question: may the principal do this action on this resource, given this context? */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Check(String action, String resource, Map<String, Object> context) {

    public Map<String, Object> contextOrEmpty() {
        return context == null ? Map.of() : context;
    }
}

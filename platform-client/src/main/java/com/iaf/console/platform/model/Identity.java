package com.iaf.console.platform.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Who is calling, as GET /api/v1/me answers it, with the delegation when the call acts for a console user. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Identity(
        String id,
        String name,
        String email,
        List<String> teams,
        List<String> roles,
        boolean admin,
        String kind,
        Delegation delegation) {
}

package com.iaf.console.bff.types.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.access.domain.Denial;

/** One verb of a type's catalog as the Actions menu draws it (P-08), with whether the session's user may run it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleAction(
        String verb,
        String title,
        String description,
        String method,
        boolean destructive,
        boolean approval,
        boolean bulk,
        boolean preview,
        String effect,
        String subjectInput,
        List<String> mutableFields,
        JsonNode input,
        String workflow,
        String version,
        Deprecation deprecated,
        Boolean allowed,
        Denial denied) {

    public ConsoleAction {
        mutableFields = mutableFields == null ? List.of() : List.copyOf(mutableFields);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Deprecation(String since, String sunset, String message) {
    }

    public ConsoleAction allowed(boolean value, Denial denial) {
        return new ConsoleAction(verb, title, description, method, destructive, approval, bulk, preview, effect, subjectInput, mutableFields, input,
                workflow, version, deprecated, value, value ? null : denial);
    }
}

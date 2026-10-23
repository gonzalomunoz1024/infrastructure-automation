package com.iaf.console.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One problem of a bad input: its code, field, level and message, with a fix when the platform knows one. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Problem(String code, String field, String level, String message, String step, Fix fix) {

    /** A fix the console can offer for a problem. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fix(String label, String kind, String value) {
    }
}

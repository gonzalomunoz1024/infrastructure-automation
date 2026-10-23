package com.iaf.console.platform.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** How the console draws a resource type: its title, icon, list columns, detail tabs and lifecycle mapping. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsoleBlock(String title, String icon, ListSpec list, DetailSpec detail, Map<String, String> lifecycle) {

    /** The list page of a type: its columns and default sort. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListSpec(List<String> columns, String defaultSort) {
    }

    /** The detail page of a type: its tabs. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DetailSpec(List<String> tabs) {
    }
}

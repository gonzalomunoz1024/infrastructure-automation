package com.iaf.console.drift.monitoring.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What a caller sends to create or change an alarm ({@code AlarmInput}): the resource, the series,
 * which side of the threshold fires, and the window whose average is compared. It arrives as typed,
 * and the service checks it before anything is stored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlarmInput(String wfrn, String name, String metric, String comparison, Double threshold, Integer windowMinutes, String actor) {

    /** The window when the input names none. */
    public static final int DEFAULT_WINDOW_MINUTES = 10;

    public int windowOrDefault() {
        return windowMinutes == null ? DEFAULT_WINDOW_MINUTES : windowMinutes;
    }
}

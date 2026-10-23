package com.iaf.console.bff.drift.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the web app sends to create or change an alarm: one series of one resource, compared with a
 * threshold over a window. {@code actor} is never the browser's to say: the BFF sets it to the
 * session's user before the drift service records it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlarmInput(String wfrn, String name, String metric, String comparison, Double threshold, Integer windowMinutes, String actor) {

    /** The input as the session's user sends it. */
    public AlarmInput by(String userId) {
        return new AlarmInput(wfrn, name, metric, comparison, threshold, windowMinutes, userId);
    }
}

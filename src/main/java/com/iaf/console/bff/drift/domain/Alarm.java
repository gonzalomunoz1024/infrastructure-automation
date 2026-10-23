package com.iaf.console.bff.drift.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An alarm as the drift service keeps it: the input, where it stands ({@code ok}, {@code alarm},
 * {@code insufficient}) since when, the window's average at the last minute's evaluation, and who
 * made it. The alarms pages and a resource's Monitoring tab show it as it comes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Alarm(
        String alarmId,
        String wfrn,
        String accountId,
        String type,
        String name,
        String metric,
        String comparison,
        Double threshold,
        Integer windowMinutes,
        String state,
        Instant stateSince,
        Double lastValue,
        Instant lastEvaluatedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static final String OK = "ok";
    public static final String ALARM = "alarm";
    public static final String INSUFFICIENT = "insufficient";
}

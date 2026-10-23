package com.iaf.console.drift.scorecards.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;

/** An account's score on a day, by pack, recorded by the daily recount: the points of the trend. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScoreSnapshot(String accountId, LocalDate day, Integer score, Map<String, Integer> packs, long failed) {

    public ScoreSnapshot {
        packs = packs == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(packs));
    }
}

package com.iaf.console.bff.insights.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.iaf.console.bff.insights.domain.DriftNumbers;

class HttpDriftStatsTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void foundIsOpenedAndFixedIsEveryFindingClosed() throws Exception {
        DriftNumbers numbers = HttpDriftStats.numbers(json.readTree("""
                {"since":"2026-09-23T12:00:00Z","until":"2026-10-23T12:00:00Z","opened":7,
                 "closed":{"total":5,"reverted":2,"accepted":1,"ignored":1,"vanished":1},"open":4,"bySeverity":{"high":3},"meanMinutesToClose":42.5}"""));
        assertEquals(new DriftNumbers(7, 5, 4, 42.5), numbers);
        assertEquals(2_550_000L, numbers.view().meanTimeToFixMs());
    }

    @Test
    void noMeanWhenNothingClosed() throws Exception {
        DriftNumbers numbers = HttpDriftStats.numbers(json.readTree("""
                {"opened":1,"closed":{"total":0,"reverted":0,"accepted":0,"ignored":0,"vanished":0},"open":1,"bySeverity":{},"meanMinutesToClose":null}"""));
        assertNull(numbers.meanMinutesToClose());
    }
}

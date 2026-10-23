package com.iaf.console.bff.insights.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.InvalidInputException;

class PeriodAndScopeTest {

    @Test
    void thePeriodIsThirtyDaysUnlessNamed() {
        assertEquals(Period.P30D, Period.parse(null));
        assertEquals(Period.P30D, Period.parse(" "));
        assertEquals(Period.P7D, Period.parse("7d"));
        assertEquals(Duration.ofDays(90), Period.parse("90D").length());
        assertThrows(InvalidInputException.class, () -> Period.parse("1y"));
    }

    @Test
    void theScopeIsTheOrganizationUnlessNamed() {
        assertEquals(InsightsScope.ORGANIZATION, InsightsScope.parse(null));
        assertEquals(InsightsScope.ACCOUNT, InsightsScope.parse("account"));
        assertThrows(InvalidInputException.class, () -> InsightsScope.parse("team"));
    }
}

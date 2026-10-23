package com.iaf.console.bff.insights.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DriftNumbersTest {

    @Test
    void accountsAddUpWithTheMeanWeightedByWhatEachClosed() {
        DriftNumbers a = new DriftNumbers(5, 3, 2, 10.0);
        DriftNumbers b = new DriftNumbers(4, 1, 6, 50.0);
        DriftNumbers sum = a.plus(b);
        assertEquals(9, sum.found());
        assertEquals(4, sum.fixed());
        assertEquals(8, sum.open());
        assertEquals(20.0, sum.meanMinutesToClose());
        assertEquals(1_200_000L, sum.view().meanTimeToFixMs());
    }

    @Test
    void anAccountThatClosedNothingLeavesTheMeanAlone() {
        DriftNumbers a = new DriftNumbers(5, 3, 2, 10.0);
        assertEquals(10.0, a.plus(new DriftNumbers(2, 0, 2, null)).meanMinutesToClose());
        assertEquals(10.0, DriftNumbers.NONE.plus(a).meanMinutesToClose());
        assertNull(DriftNumbers.NONE.plus(DriftNumbers.NONE).view().meanTimeToFixMs());
    }

    @Test
    void anAccountTheServiceDidNotAnswerForMakesTheSumPartial() {
        DriftNumbers a = new DriftNumbers(5, 3, 2, 10.0);
        DriftNumbers sum = a.plus(DriftNumbers.UNANSWERED);
        assertEquals(5, sum.found());
        assertEquals(10.0, sum.meanMinutesToClose());
        assertTrue(sum.view().partial());
        assertNull(a.plus(DriftNumbers.NONE).view().partial(), "a whole answer says nothing about it");
    }
}

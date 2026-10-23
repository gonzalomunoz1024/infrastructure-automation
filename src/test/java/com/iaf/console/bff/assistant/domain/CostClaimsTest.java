package com.iaf.console.bff.assistant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The copilot states a cost only as the cost service answered it. */
class CostClaimsTest {

    @Test
    void aSentenceWithAnotherAmountIsDropped() {
        List<String> notes = new ArrayList<>();
        String kept = CostClaims.only("web-01 is a VM. It costs about $120 a month. It has two disks.", new BigDecimal("73.20"), notes);
        assertEquals("web-01 is a VM. It has two disks.", kept);
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("$120"), notes.toString());
    }

    @Test
    void theCostServicesAmountStays() {
        List<String> notes = new ArrayList<>();
        assertEquals("It costs 73.20 USD a month.", CostClaims.only("It costs 73.20 USD a month.", new BigDecimal("73.2"), notes));
        assertEquals("It costs $73.2 a month.", CostClaims.only("It costs $73.2 a month.", new BigDecimal("73.20"), notes));
        assertEquals("That is 73.199 dollars.", CostClaims.only("That is 73.199 dollars.", new BigDecimal("73.20"), notes), "within a cent");
        assertTrue(notes.isEmpty(), notes.toString());
    }

    @Test
    void withoutACostEveryAmountGoes() {
        List<String> notes = new ArrayList<>();
        assertNull(CostClaims.only("It costs €40.", null, notes));
        assertEquals("Restart it.\n\nNothing else.", CostClaims.only("Restart it. A restart costs 5 EUR.\n\nNothing else.", null, notes));
        assertEquals(2, notes.size());
    }

    @Test
    void textWithoutMoneyIsUntouched() {
        List<String> notes = new ArrayList<>();
        String text = "It has 16 GB of memory and 4 CPUs.\n\nIt runs in prod.";
        assertEquals(text, CostClaims.only(text, null, notes));
        assertNull(CostClaims.only(null, null, notes));
        assertTrue(notes.isEmpty());
    }
}

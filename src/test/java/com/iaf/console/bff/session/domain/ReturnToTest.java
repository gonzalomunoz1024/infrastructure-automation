package com.iaf.console.bff.session.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReturnToTest {

    @Test
    void onlyLocalPathsAreSafe() {
        assertTrue(ReturnTo.isSafe("/"));
        assertTrue(ReturnTo.isSafe("/#/vms?page=2"));
        assertTrue(ReturnTo.isSafe("/console/x"));
        assertFalse(ReturnTo.isSafe("//evil.example/x"));
        assertFalse(ReturnTo.isSafe("/\\evil.example"));
        assertFalse(ReturnTo.isSafe("https://evil.example"));
        assertFalse(ReturnTo.isSafe("javascript:alert(1)"));
        assertFalse(ReturnTo.isSafe("/x\r\nSet-Cookie: a=b"));
        assertFalse(ReturnTo.isSafe(""));
        assertFalse(ReturnTo.isSafe(null));
        assertFalse(ReturnTo.isSafe("/" + "a".repeat(3000)));
    }

    @Test
    void sanitizeFallsBackToTheRoot() {
        assertEquals("/#/vms", ReturnTo.sanitize("/#/vms"));
        assertEquals("/", ReturnTo.sanitize("//evil.example"));
        assertEquals("/", ReturnTo.sanitize(null));
    }
}

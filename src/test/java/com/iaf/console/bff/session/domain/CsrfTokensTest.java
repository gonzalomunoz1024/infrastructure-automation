package com.iaf.console.bff.session.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class CsrfTokensTest {

    private final CsrfTokens tokens = new CsrfTokens("a secret of at least thirty-two bytes!!".getBytes(StandardCharsets.UTF_8));

    @Test
    void aTokenIsBoundToItsSession() {
        String token = tokens.tokenFor("session-1");
        assertEquals(token, tokens.tokenFor("session-1"));
        assertNotEquals(token, tokens.tokenFor("session-2"));
        assertTrue(tokens.matches("session-1", token));
        assertFalse(tokens.matches("session-2", token));
        assertFalse(tokens.matches("session-1", token + "x"));
        assertFalse(tokens.matches("session-1", ""));
        assertFalse(tokens.matches("session-1", null));
    }

    @Test
    void anotherSecretGivesAnotherToken() {
        CsrfTokens other = new CsrfTokens("another secret of at least thirty-two bytes".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(tokens.tokenFor("s"), other.tokenFor("s"));
        assertFalse(other.matches("s", tokens.tokenFor("s")));
    }
}

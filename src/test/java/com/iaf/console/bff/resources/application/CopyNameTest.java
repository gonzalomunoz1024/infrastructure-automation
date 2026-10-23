package com.iaf.console.bff.resources.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A copy's suggested name fits the create's name field: {@code <name>-copy}, the name shortened when the field is short. */
class CopyNameTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theCopysNameFitsTheField() throws Exception {
        JsonNode vmName = JSON.readTree("{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]{2,30}$\"}");
        assertEquals("web-03-copy", Resources.copyName("web-03", vmName));
        assertEquals("abcdefghijklmnopqrstuvwxyz-copy", Resources.copyName("abcdefghijklmnopqrstuvwxyz0123", vmName), "shortened to 31 characters");
        assertEquals("web-copy", Resources.copyName("web-", vmName), "no double dash");
        assertEquals("db-copy", Resources.copyName("db", JSON.readTree("{\"type\":\"string\",\"maxLength\":8}")));
        assertNull(Resources.copyName("db", JSON.readTree("{\"type\":\"string\",\"pattern\":\"^[0-9]+$\"}")), "nothing fits");
        assertNull(Resources.copyName("", vmName));
    }
}

package com.iaf.console.bff.assistant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A workflow's form as the copilot fills it: never a secret field, only values that fit, and the required fields still empty. */
class InputFormTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode createVm() throws Exception {
        return JSON.readTree("""
                {"type": "object", "required": ["name", "image", "size", "adminPassword"],
                 "properties": {
                   "name": {"type": "string", "title": "VM name", "pattern": "^[a-z][a-z0-9-]{2,30}$", "x-internal": "kept back"},
                   "image": {"type": "string", "x-resource": {"type": "image"}},
                   "size": {"type": "string", "enum": ["small", "medium", "large"], "default": "small"},
                   "cpu": {"type": "integer", "minimum": 1, "maximum": 16},
                   "tags": {"type": "array", "items": {"type": "string"}},
                   "adminPassword": {"type": "string", "writeOnly": true},
                   "sshKey": {"type": "string", "x-secret": true},
                   "apiToken": {"type": "string"}
                 }}""");
    }

    @Test
    void anAssistantSeesNoSecretField() throws Exception {
        JsonNode shown = InputForm.of(createVm()).forAgent();
        assertEquals(List.of("name", "image", "size", "cpu", "tags"), List.copyOf(names(shown.path("properties"))));
        assertFalse(shown.path("properties").path("name").has("x-internal"), "only the parts of a field that help fill it");
        assertEquals("^[a-z][a-z0-9-]{2,30}$", shown.path("properties").path("name").path("pattern").asText());
        assertEquals(List.of("name", "image", "size"), List.copyOf(texts(shown.path("required"))), "a required secret is the person's to fill");
        assertNull(InputForm.of(null).forAgent());
    }

    @Test
    void onlyValuesThatFitAreFilled() throws Exception {
        List<String> notes = new ArrayList<>();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "web-9");
        input.put("size", "huge");
        input.put("cpu", 64);
        input.put("tags", List.of("a", "b"));
        input.put("adminPassword", "hunter2");
        input.put("apiToken", "x");
        input.put("colour", "blue");
        Map<String, Object> filled = InputForm.of(createVm()).fill(input, notes);
        assertEquals(Map.of("name", "web-9", "tags", List.of("a", "b")), filled);
        assertEquals(5, notes.size(), notes.toString());
        assertTrue(notes.contains("input 'adminPassword' is a secret field"), notes.toString());

        List<String> more = new ArrayList<>();
        assertEquals(Map.of(), InputForm.of(createVm()).fill(Map.of("name", "Web 9", "cpu", 2.5), more));
        assertEquals(2, more.size(), more.toString());
        assertTrue(more.containsAll(List.of("input 'name' does not match the field's pattern", "input 'cpu' is not a whole number")), more.toString());
    }

    @Test
    void theMissingFieldsAreTheRequiredOnesWithoutAValueOrADefault() throws Exception {
        InputForm form = InputForm.of(createVm());
        assertEquals(List.of("image"), form.missing(Map.of("name", "web-9")));
        assertEquals(List.of("name", "image"), form.missing(Map.of()));
    }

    @Test
    void aVerbsFormLeavesOutItsSubject() throws Exception {
        JsonNode restart = JSON.readTree("""
                {"type": "object", "required": ["vmId", "reason"], "properties": {"vmId": {"type": "string"}, "reason": {"type": "string"}}}""");
        InputForm form = InputForm.of(restart).without("vmId");
        assertEquals(List.of("reason"), form.required());
        assertFalse(form.forAgent().path("properties").has("vmId"));
        assertTrue(InputForm.of(restart).forAgent().path("properties").has("vmId"), "the original form is unchanged");
    }

    @Test
    void attributesAndInputLoseTheirSecretLookingFields() throws Exception {
        JsonNode redacted = InputForm.redacted(JSON.readTree("""
                {"name": "db-1", "password": "x", "nested": {"privateKey": "y", "port": 5432}, "list": [{"token": "z", "ok": true}]}"""));
        assertEquals(JSON.readTree("""
                {"name": "db-1", "nested": {"port": 5432}, "list": [{"ok": true}]}"""), redacted);
        assertEquals(Map.of("name", "web-9"), InputForm.of(createVm()).shown(Map.of("name", "web-9", "adminPassword", "x", "unknown", 1)));
    }

    private static List<String> names(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.properties().forEach(e -> names.add(e.getKey()));
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(n -> texts.add(n.asText()));
        return texts;
    }
}

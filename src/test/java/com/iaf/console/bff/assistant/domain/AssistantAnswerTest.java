package com.iaf.console.bff.assistant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** An answer in the answer schema's JSON is read leniently, so the review decides on what is there. */
class AssistantAnswerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyPartOfAnAnswerIsRead() throws Exception {
        List<String> notes = new ArrayList<>();
        AssistantAnswer answer = AssistantAnswer.read(JSON.readTree("""
                {"text": "Here.",
                 "proposals": [
                   {"kind": "filter", "label": "Failed VMs", "service": "compute", "type": "vm", "filter": {"state": "failed"}, "sort": "-created"},
                   {"kind": "create", "label": "Create web-9", "type": "vm", "input": {"name": "web-9", "cpu": 4, "ratio": 0.5, "flags": [true]}, "missing": ["image", 3]},
                   {"kind": "action", "label": "Restart", "wfrn": "wfrn:wf:compute:global:123456789012:vm/web-01", "verb": "restart"},
                   {"kind": "open", "label": "Quotas", "path": "/console/quotas"},
                   "nonsense"
                 ],
                 "explanation": {"summary": "It is fine.", "sections": [{"title": "Drift", "text": "None."}, {"title": "No text"}]},
                 "failure": {"title": "Full", "fix": "Ask for less.", "input": {"memoryGb": 16}, "confidence": "medium"}}"""), notes);
        assertEquals("Here.", answer.text());
        assertEquals(4, answer.proposals().size());
        assertEquals("-created", answer.proposals().get(0).sort());
        assertEquals("failed", answer.proposals().get(0).filter().state());
        assertEquals(Map.of("name", "web-9", "cpu", 4, "ratio", 0.5, "flags", List.of(true)), answer.proposals().get(1).input());
        assertEquals(List.of("image"), answer.proposals().get(1).missing());
        assertEquals("restart", answer.proposals().get(2).verb());
        assertEquals("/console/quotas", answer.proposals().get(3).path());
        assertEquals("It is fine.", answer.explanation().summary());
        assertEquals(1, answer.explanation().sections().size());
        assertEquals(Map.of("memoryGb", 16), answer.failure().input());
        assertEquals("medium", answer.failure().confidence());
        assertEquals(List.of("a proposal is not an object", "a section without a title or a text"), notes);
    }

    @Test
    void aRefusalIsRead() throws Exception {
        AssistantAnswer refused = AssistantAnswer.read(JSON.readTree("{\"refused\": true, \"reason\": \"I only answer about this console.\"}"), new ArrayList<>());
        assertTrue(refused.refused());
        assertEquals("I only answer about this console.", refused.reason());
        assertNull(refused.text());
    }

    @Test
    void jsonThatIsNoAnswerIsRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AssistantAnswer.read(JSON.readTree("{}"), new ArrayList<>()));
        assertThrows(IllegalArgumentException.class, () -> AssistantAnswer.read(JSON.readTree("{\"answer\": \"hi\"}"), new ArrayList<>()));
        assertThrows(IllegalArgumentException.class, () -> AssistantAnswer.read(JSON.readTree("[1, 2]"), new ArrayList<>()));
        assertThrows(IllegalArgumentException.class, () -> AssistantAnswer.read(null, new ArrayList<>()));
        List<String> notes = new ArrayList<>();
        AssistantAnswer odd = AssistantAnswer.read(JSON.readTree("{\"text\": \"ok\", \"proposals\": \"all of them\", \"explanation\": 3}"), notes);
        assertEquals("ok", odd.text());
        assertTrue(odd.proposals().isEmpty());
        assertEquals(List.of("proposals is not a list", "explanation is not an object"), notes);
    }
}

package com.iaf.console.bff.assistant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.Problem;

/** A turn's body: what makes it a 400, and what of its context the copilot keeps. */
class AssistantAskTest {

    private static final String WFRN = "wfrn:wf:compute:global:123456789012:vm/web-01";

    @Test
    void aGoodAskHasNoProblems() {
        assertTrue(new AssistantAsk(null, "ask", "show the failed vms", null).problems().isEmpty());
        assertTrue(new AssistantAsk("conv_1", "explain", null, new AskContext(null, null, null, WFRN, null)).problems().isEmpty());
        assertTrue(new AssistantAsk(null, "ask", "x".repeat(2000), null).problems().isEmpty());
        assertTrue(new AssistantAsk(null, "ask", "🙂".repeat(2000), null).problems().isEmpty(), "characters, not UTF-16 units");
    }

    @Test
    void eachProblemIsAtItsPointer() {
        assertEquals(List.of("/intent required"), problems(AssistantAsk.empty()));
        assertEquals(List.of("/intent enum"), problems(new AssistantAsk(null, "chat", "hi", null)));
        assertEquals(List.of("/message required"), problems(new AssistantAsk(null, "ask", "  ", null)));
        assertEquals(List.of("/message length"), problems(new AssistantAsk(null, "ask", "x".repeat(2001), null)));
        assertEquals(List.of("/context/wfrn required"), problems(new AssistantAsk(null, "explain", null, null)));
        assertEquals(List.of("/context/wfrn required"), problems(new AssistantAsk(null, "explain", "why?", new AskContext("/console/r/vm", null, null, " ", null))));
        assertEquals(List.of("/conversationId required"), problems(new AssistantAsk("", "ask", "hi", null)));
        assertEquals("the ask has 2 problems: intent is ask or explain; message is at most 2000 characters",
                AssistantAsk.invalid(new AssistantAsk(null, "chat", "x".repeat(2001), null).problems()).getMessage());
    }

    @Test
    void theContextKeepsOnlyWhatLooksRight() {
        AssistantAsk ask = new AssistantAsk(null, "ask", "  why?  ", new AskContext("https://evil.example/console/", "Compute Service", "vm", " " + WFRN + " ",
                new SearchFilter(null, "nonsense", true, null, Map.of("env", "prod")))).sanitized();
        assertEquals("why?", ask.message());
        assertNull(ask.context().path());
        assertNull(ask.context().service());
        assertEquals("vm", ask.context().type());
        assertEquals(WFRN, ask.context().wfrn());
        assertEquals(new SearchFilter(null, null, true, null, Map.of("env", "prod")), ask.context().filter());
        assertEquals("/console/r/vm?state=failed", new AskContext("/console/r/vm?state=failed", null, null, null, null).sanitized().path());
    }

    @Test
    void consolePathsStayInTheConsole() {
        assertTrue(ConsolePaths.isConsolePath("/console/r/vm"));
        assertTrue(ConsolePaths.isConsolePath("/console/r/vm/wfrn%3Awf%3Acompute?tab=drift#top"));
        for (String path : List.of("/consolex", "/console//evil.example", "https://evil.example/console/", "/console/../admin", "/console/%2e%2e/admin",
                "/console/r\\vm", "/console/r/vm<script>", "/console/r vm", "javascript:alert(1)", "/api/console/")) {
            assertFalse(ConsolePaths.isConsolePath(path), path);
        }
    }

    private static List<String> problems(AssistantAsk ask) {
        return ask.problems().stream().map(AssistantAskTest::describe).toList();
    }

    private static String describe(Problem p) {
        return p.field() + " " + p.code();
    }
}

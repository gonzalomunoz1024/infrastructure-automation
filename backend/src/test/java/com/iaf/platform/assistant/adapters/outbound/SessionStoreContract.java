package com.iaf.platform.assistant.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.ports.outbound.SessionStore;

/** What every {@link SessionStore} does, in memory and in a database. */
abstract class SessionStoreContract {

    static final Instant NOW = Instant.parse("2026-10-17T12:00:00.123456789Z");
    static final ObjectMapper JSON = new ObjectMapper();

    /** An empty store. */
    abstract SessionStore newStore();

    /** A new store over the same storage as the last one made; null when the storage does not outlive the store. */
    abstract SessionStore reopen();

    static AssistantSession session(String id) {
        ObjectNode draft = JSON.createObjectNode().put("workflowYaml", "kind: Workflow\nmetadata: { name: hello }\n").put("valid", true).put("score", 0.75);
        draft.putArray("problems").add("none yet");
        return new AssistantSession(id, AssistantSession.CREATE, "hello", "ana", AssistantSession.IDLE, draft,
                List.of(new AssistantSession.Turn("user", "make a workflow that says hello ✓", NOW)), "ext-42", NOW, NOW.plusNanos(1));
    }

    @Test
    void readsASessionBackExactly() {
        SessionStore store = newStore();
        AssistantSession s = session("as_1");
        store.save(s);
        assertThat(store.get("as_1")).contains(s);
        assertThat(store.get("as_1").orElseThrow().currentYaml()).startsWith("kind: Workflow");
        assertThat(store.get("as_unknown")).isEmpty();
        assertThat(store.get(null)).isEmpty();
    }

    @Test
    void savingAgainReplacesTheSession() {
        SessionStore store = newStore();
        store.save(session("as_1"));
        AssistantSession done = session("as_1").withStatus(AssistantSession.DONE, NOW.plusSeconds(5));
        store.save(done);
        assertThat(store.get("as_1")).contains(done);
    }

    @Test
    void updatesASessionAndReturnsTheNewValue() {
        SessionStore store = newStore();
        store.save(session("as_1"));
        AssistantSession expected = session("as_1").withTurn("assistant", "here it is", NOW.plusSeconds(1)).withStatus(AssistantSession.WORKING, NOW.plusSeconds(1));
        assertThat(store.update("as_1", s -> s.withTurn("assistant", "here it is", NOW.plusSeconds(1)).withStatus(AssistantSession.WORKING, NOW.plusSeconds(1))))
                .contains(expected);
        assertThat(store.get("as_1")).contains(expected);
    }

    @Test
    void updatingAnUnknownSessionChangesNothing() {
        SessionStore store = newStore();
        AtomicInteger calls = new AtomicInteger();
        assertThat(store.update("as_unknown", s -> {
            calls.incrementAndGet();
            return s;
        })).isEmpty();
        assertThat(store.update(null, s -> s)).isEmpty();
        assertThat(calls).hasValue(0);
        assertThat(store.get("as_unknown")).isEmpty();
    }

    @Test
    void aChangeToNothingRemovesTheSession() {
        SessionStore store = newStore();
        store.save(session("as_1"));
        assertThat(store.update("as_1", s -> null)).isEmpty();
        assertThat(store.get("as_1")).isEmpty();
    }

    @Test
    void concurrentUpdatesLoseNoTurn() throws Exception {
        SessionStore store = newStore();
        store.save(session("as_1"));
        int writers = 4;
        int each = 15;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                work.add(() -> {
                    for (int i = 0; i < each; i++) {
                        String text = "turn " + writer + "." + i;
                        store.update("as_1", s -> s.withTurn("user", text, NOW));
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(work)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.get("as_1").orElseThrow().history()).hasSize(1 + writers * each);
    }

    @Test
    void keepsSessionsAcrossARestart() {
        SessionStore store = newStore();
        AssistantSession s = session("as_1");
        store.save(s);
        SessionStore after = reopen();
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.get("as_1")).contains(s);
        assertThat(after.update("as_1", x -> x.withStatus(AssistantSession.DONE, NOW))).map(AssistantSession::status).contains(AssistantSession.DONE);
    }
}

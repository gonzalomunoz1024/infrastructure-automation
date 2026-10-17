package com.iaf.platform.assistant.ports.outbound;

import java.util.Optional;
import java.util.function.UnaryOperator;

import com.iaf.platform.assistant.domain.AssistantSession;

/** Where builder sessions live while the UI talks to them: memory in the demo, a store later. */
public interface SessionStore {

    void save(AssistantSession session);

    Optional<AssistantSession> get(String id);

    /** Atomically replace a session; returns the new value, empty when unknown. */
    Optional<AssistantSession> update(String id, UnaryOperator<AssistantSession> change);
}

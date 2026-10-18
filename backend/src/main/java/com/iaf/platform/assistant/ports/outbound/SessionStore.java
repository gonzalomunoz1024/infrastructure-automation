package com.iaf.platform.assistant.ports.outbound;

import java.time.Instant;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.iaf.platform.assistant.domain.AssistantSession;

/** Where builder sessions live while the UI talks to them: memory in the demo, MongoDB under {@code mongo}. */
public interface SessionStore {

    void save(AssistantSession session);

    Optional<AssistantSession> get(String id);

    /** Atomically replace a session; returns the new value, empty when unknown. */
    Optional<AssistantSession> update(String id, UnaryOperator<AssistantSession> change);

    /** Deletes the sessions last updated before {@code cutoff}; returns how many. */
    int deleteUpdatedBefore(Instant cutoff);
}

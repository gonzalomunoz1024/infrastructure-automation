package com.iaf.platform.assistant.adapters.outbound;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.ports.outbound.SessionStore;

@Profile("!postgres")
@Component
public class InMemorySessionStore implements SessionStore {

    private final Map<String, AssistantSession> byId = new ConcurrentHashMap<>();

    @Override
    public void save(AssistantSession session) {
        byId.put(session.id(), session);
    }

    @Override
    public Optional<AssistantSession> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<AssistantSession> update(String id, UnaryOperator<AssistantSession> change) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.computeIfPresent(id, (k, s) -> change.apply(s)));
    }
}

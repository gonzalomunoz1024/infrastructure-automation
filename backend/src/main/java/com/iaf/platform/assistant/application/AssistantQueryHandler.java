package com.iaf.platform.assistant.application;

import org.springframework.stereotype.Service;

import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.ports.inbound.AssistantQueries;
import com.iaf.platform.assistant.ports.outbound.SessionStore;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AssistantQueryHandler implements AssistantQueries {

    private final SessionStore sessions;

    @Override
    public Mono<AssistantSession> session(String id) {
        return Mono.defer(() -> Mono.justOrEmpty(sessions.get(id)));
    }
}
